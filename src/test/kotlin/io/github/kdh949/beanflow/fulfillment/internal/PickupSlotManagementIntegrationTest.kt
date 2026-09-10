package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.identity.internal.CustomerPasswordSecurity
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, PickupManagementClockConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies pickup management with real sessions and concurrent reservation transactions")
@SpringBootTest
internal class PickupSlotManagementIntegrationTest(
    @Autowired private val service: PickupSlotManagementService,
    @Autowired private val reservations: PickupReservationOperations,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val passwords: CustomerPasswordSecurity,
    @Autowired private val clock: PickupManagementTestClock,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach fun clean() {
        clock.set(Instant.now())
        jdbc.execute(
            "TRUNCATE fulfillment_pickup_slot, identity_store_membership, identity_merchant_account, " +
                "merchant_store, operations_audit_record, spring_session CASCADE",
        )
    }

    @Test fun `OWNER and STAFF create read and replace slots using actual Merchant Session and CSRF`() {
        for (role in listOf(
            "OWNER",
            "STAFF",
        )) {
            val c = fixture(role)
            val login = "pickup.${c.actorId.toString().take(8)}"
            val password = "pickup-management-password-2026"
            jdbc.update(
                "UPDATE identity_merchant_account SET login_id = ?, password_hash = ? WHERE id = ?",
                login,
                passwords.encode(password),
                c.actorId,
            )
            val csrf =
                requireNotNull(
                    mvc
                        .perform(get("/api/v1/auth/merchant/csrf"))
                        .andReturn()
                        .response
                        .getCookie("BEANFLOW_MERCHANT_XSRF"),
                )
            val session =
                requireNotNull(
                    mvc
                        .perform(
                            post("/api/v1/auth/merchant/sessions")
                                .cookie(csrf)
                                .header(
                                    "X-BEANFLOW-CSRF",
                                    csrf.value,
                                ).contentType(MediaType.APPLICATION_JSON)
                                .content("""{"loginId":"$login","password":"$password"}"""),
                        ).andExpect(status().isOk)
                        .andReturn()
                        .response
                        .getCookie("BEANFLOW_MERCHANT_SESSION"),
                )
            val path = "/api/v1/stores/${c.storeId}/pickup-slot-management"
            val body =
                mapper.writeValueAsString(
                    CreatePickupSlotRequest(
                        c.startsAt,
                        c.endsAt,
                        5,
                        c.reason,
                    ),
                )
            mvc
                .perform(
                    post(path)
                        .cookie(
                            session,
                            csrf,
                        ).header(
                            "Idempotency-Key",
                            c.key,
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
            val response =
                mvc
                    .perform(
                        post(path)
                            .cookie(
                                session,
                                csrf,
                            ).header(
                                "X-BEANFLOW-CSRF",
                                csrf.value,
                            ).header(
                                "Idempotency-Key",
                                c.key,
                            ).contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.reservedCount").value(0))
                    .andReturn()
                    .response.contentAsString
            val slot =
                mapper.readValue(
                    response,
                    ManagedPickupSlot::class.java,
                )
            mvc.perform(get("$path/${slot.slotId}").cookie(session)).andExpect(status().isOk).andExpect(jsonPath("$.capacity").value(5))
            mvc
                .perform(
                    get(path)
                        .cookie(session)
                        .param(
                            "from",
                            c.startsAt.minusSeconds(1).toString(),
                        ).param(
                            "to",
                            c.endsAt.toString(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
            mvc
                .perform(
                    put("$path/${slot.slotId}")
                        .cookie(
                            session,
                            csrf,
                        ).header(
                            "X-BEANFLOW-CSRF",
                            csrf.value,
                        ).header(
                            "Idempotency-Key",
                            "pickup-http-replace-$role",
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                ReplacePickupSlotRequest(
                                    slot.startsAt,
                                    slot.endsAt,
                                    0,
                                    slot.version,
                                    c.reason,
                                ),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.capacity").value(0))
            assertThat(
                jdbc.queryForObject(
                    "SELECT actor_type FROM operations_audit_record WHERE target_id = ? AND action = 'PICKUP_SLOT_CREATED'",
                    String::class.java,
                    slot.slotId,
                ),
            ).isEqualTo(if (role == "OWNER") "STORE_OWNER" else "STORE_STAFF")
        }
    }

    @Test fun `reserved and confirmed usage survives capacity changes and forbids moving the window`() {
        val c = fixture()
        val slot = service.change(c.copy(capacity = 3))
        val order = UUID.randomUUID()
        transactions.executeWithoutResult {
            reservations.reserve(
                ReservePickupCommand(
                    order,
                    c.storeId,
                    slot.slotId,
                    c.startsAt,
                    "management-reserve:$order",
                ),
            )
        }
        val reserved =
            service.get(
                c.actorId,
                c.storeId,
                slot.slotId,
            )
        val replace =
            c.copy(
                slotId = slot.slotId,
                key = "pickup-resize-key",
                expectedVersion = reserved.version,
            )
        assertConflict {
            service.change(replace.copy(capacity = 0))
        }
        assertConflict {
            service.change(replace.copy(startsAt = c.startsAt.plusSeconds(1)))
        }
        val resized = service.change(replace.copy(capacity = 4))
        assertThat(resized.reservedCount).isOne()
        transactions.executeWithoutResult {
            reservations.confirm(
                order,
                Instant.now(),
                "management-reserve:$order",
            )
        }
        val confirmed =
            service.get(
                c.actorId,
                c.storeId,
                slot.slotId,
            )
        assertThat(confirmed.confirmedCount).isOne()
        assertConflict {
            service.change(
                replace.copy(
                    key = "pickup-move-key",
                    expectedVersion = confirmed.version,
                    endsAt = c.endsAt.plusSeconds(1),
                ),
            )
        }
        assertThatThrownBy {
            jdbc.update(
                "UPDATE fulfillment_pickup_slot SET ends_at = ends_at + interval '1 second' WHERE id = ?",
                slot.slotId,
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test fun `same command replays original response while stale version changed payload and revoked membership fail`() {
        val c = fixture()
        val first = service.change(c)
        service.change(
            c.copy(
                slotId = first.slotId,
                key = "pickup-next-key",
                expectedVersion = 0,
                capacity = 6,
            ),
        )
        assertThat(service.change(c)).isEqualTo(first)
        assertThatThrownBy {
            service.change(c.copy(capacity = 9))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
        }
        assertConflict {
            service.change(
                c.copy(
                    slotId = first.slotId,
                    key = "pickup-stale-key",
                    expectedVersion = 0,
                    capacity = 7,
                ),
            )
        }
        jdbc.update(
            "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ?",
            c.actorId,
        )
        assertThatThrownBy {
            service.change(c)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
        }
    }

    @Test fun `cross-store access invalid ranges and started slots cannot be changed`() {
        val c = fixture()
        val slot = service.change(c)
        val other = fixture()
        assertThatThrownBy {
            service.get(
                other.actorId,
                other.storeId,
                slot.slotId,
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
        }
        assertThatThrownBy {
            service.change(
                c.copy(
                    key = "pickup-invalid-key",
                    endsAt = c.startsAt,
                ),
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
        assertThatThrownBy {
            service.change(
                c.copy(
                    key = "pickup-negative-key",
                    capacity = -1,
                ),
            )
        }.isInstanceOf(DomainFailure::class.java)
        jdbc.update(
            "UPDATE fulfillment_pickup_slot SET starts_at = ?, ends_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)),
            Timestamp.from(Instant.now().plusSeconds(60)),
            slot.slotId,
        )
        assertConflict {
            service.change(
                c.copy(
                    slotId = slot.slotId,
                    expectedVersion = 0,
                    key = "pickup-started-key",
                ),
            )
        }
    }

    @Test fun `Audit failure rolls back slot and ledger and signed list stays scoped`() {
        val c = fixture()
        jdbc.execute("ALTER TABLE operations_audit_record ADD CONSTRAINT test_pickup_audit CHECK (action <> 'PICKUP_SLOT_CREATED')")
        try {
            assertThatThrownBy {
                service.change(c)
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_pickup_audit")
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM fulfillment_pickup_slot",
                Long::class.java,
            ),
        ).isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM fulfillment_pickup_slot_command",
                Long::class.java,
            ),
        ).isZero()
        repeat(3) {
            service.change(
                c.copy(
                    key = "pickup-page-key-$it",
                    startsAt = c.startsAt.plusSeconds(it.toLong()),
                ),
            )
        }
        val page =
            service.list(
                c.actorId,
                c.storeId,
                c.startsAt,
                c.endsAt,
                null,
                2,
            )
        val next =
            service.list(
                c.actorId,
                c.storeId,
                c.startsAt,
                c.endsAt,
                page.nextCursor,
                2,
            )
        assertThat(page.items + next.items).hasSize(3).doesNotHaveDuplicates()
        assertThatThrownBy {
            service.list(
                c.actorId,
                c.storeId,
                c.startsAt.minusSeconds(1),
                c.endsAt,
                page.nextCursor,
                2,
            )
        }.isInstanceOf(DomainFailure::class.java)
    }

    @Test fun `reservation and capacity zero race cannot oversubscribe the slot`() {
        val c = fixture()
        val slot = service.change(c.copy(capacity = 1))
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val resize =
                pool.submit<Boolean> {
                    barrier.await(
                        5,
                        TimeUnit.SECONDS,
                    )
                    try {
                        service.change(
                            c.copy(
                                slotId = slot.slotId,
                                key = "pickup-race-resize",
                                expectedVersion = 0,
                                capacity = 0,
                            ),
                        )
                        true
                    } catch (failure: DomainFailure) {
                        assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                        false
                    }
                }
            val reserve =
                pool.submit<Boolean> {
                    barrier.await(
                        5,
                        TimeUnit.SECONDS,
                    )
                    val order = UUID.randomUUID()
                    try {
                        transactions.executeWithoutResult {
                            reservations.reserve(
                                ReservePickupCommand(
                                    order,
                                    c.storeId,
                                    slot.slotId,
                                    c.startsAt,
                                    "pickup-race:$order",
                                ),
                            )
                        }
                        true
                    } catch (failure: DomainFailure) {
                        assertThat(failure.code).isEqualTo(FailureCode.PICKUP_SLOT_FULL)
                        false
                    }
                }
            assertThat(
                listOf(
                    resize.get(
                        20,
                        TimeUnit.SECONDS,
                    ),
                    reserve.get(
                        20,
                        TimeUnit.SECONDS,
                    ),
                ),
            ).containsExactlyInAnyOrder(
                true,
                false,
            )
            val current =
                service.get(
                    c.actorId,
                    c.storeId,
                    slot.slotId,
                )
            assertThat(current.reservedCount + current.confirmedCount).isLessThanOrEqualTo(current.capacity)
        } finally {
            pool.shutdownNow()
            assertThat(
                pool.awaitTermination(
                    30,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
    }

    @Test fun `slot that starts while waiting for its row lock cannot be changed`() {
        val c = fixture()
        val slot = service.change(c)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerPid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    transactions.execute {
                        jdbc.queryForObject("SELECT id FROM fulfillment_pickup_slot WHERE id = ? FOR UPDATE", UUID::class.java, slot.slotId)
                        locked.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            check(locked.await(5, TimeUnit.SECONDS))
            val writer =
                pool.submit<FailureCode?> {
                    try {
                        transactions.execute {
                            writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                            service.change(
                                c.copy(slotId = slot.slotId, key = "pickup-waited-key", expectedVersion = slot.version, capacity = 6),
                            )
                        }
                        null
                    } catch (failure: DomainFailure) {
                        failure.code
                    }
                }
            await().atMost(Duration.ofSeconds(5)).until {
                writerPid.get() != 0 &&
                    jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, writerPid.get()) == true
            }
            clock.set(c.startsAt.plusSeconds(1))
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
            assertThat(service.get(c.actorId, c.storeId, slot.slotId)).isEqualTo(slot)
            assertThat(service.change(c)).isEqualTo(slot)
        } finally {
            release.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(role: String = "OWNER"): PickupSlotManagementCommand {
        val store = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        jdbc.update(
            "INSERT INTO merchant_store(id, accepting_orders, pickup_enabled, version) VALUES (?, false, false, 0)",
            store,
        )
        MerchantAccountDatabaseFixture.insertActive(
            jdbc,
            actor,
        )
        jdbc.update(
            "INSERT INTO identity_store_membership(id, actor_id, store_id, membership_role, status, created_at, updated_at, version) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
            UUID.randomUUID(),
            actor,
            store,
            role,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return PickupSlotManagementCommand(
            actor,
            store,
            null,
            "pickup-create-key",
            now.plusSeconds(3600),
            now.plusSeconds(3900),
            5,
            null,
            "픽업 운영 확인",
        )
    }

    private fun assertConflict(block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class PickupManagementClockConfiguration {
    @Bean @Primary
    fun pickupManagementClock() = PickupManagementTestClock()
}

internal class PickupManagementTestClock : Clock() {
    private val now = AtomicReference(Instant.now())

    fun set(value: Instant) {
        now.set(value)
    }

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
