package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.ManagedStoreSettlementTerms
import io.github.kdh949.beanflow.merchant.api.RegisterStoreSettlementTermsCommand
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsOperations
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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, TermsManagementClockConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies immutable terms registration, operator HTTP, and committed Store lock races")
@SpringBootTest
internal class StoreSettlementTermsManagementIntegrationTest(
    @Autowired private val service: OperatorStoreSettlementTermsService,
    @Autowired private val source: StoreSettlementTermsOperations,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val clock: TermsManagementTestClock,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    @BeforeEach fun clean() {
        clock.set(Instant.now())
        jdbc.execute("TRUNCATE merchant_store, operations_operator_permission_grant, operations_audit_record CASCADE")
    }

    @Test fun `HTTP registers lists and reads an immutable terms version with strict input`() {
        val c = command()
        val request =
            RegisterStoreSettlementTermsRequest(
                c.sourceReference,
                c.feeRateBps,
                c.effectiveFrom,
                c.effectiveTo,
                0,
                c.reason,
            )
        val body =
            mvc
                .perform(
                    post(path(c))
                        .with(jwt(c.actorId))
                        .header(
                            "Idempotency-Key",
                            c.key,
                        ).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn()
                .response.contentAsString
        val result =
            mapper.readValue(
                body,
                ManagedStoreSettlementTerms::class.java,
            )
        mvc.perform(get(path(c)).with(jwt(c.actorId))).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
        mvc
            .perform(get("${path(c)}/${result.terms.termsVersionId}").with(jwt(c.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.terms.feeRateBps").value(250))
        mvc
            .perform(
                post(path(c))
                    .with(jwt(c.actorId))
                    .header(
                        "Idempotency-Key",
                        "terms-unknown-field",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(mapper.writeValueAsString(request).dropLast(1) + ",\"actorId\":\"${c.actorId}\"}"),
            ).andExpect(status().isBadRequest)
        mvc.perform(get(path(c))).andExpect(status().isUnauthorized)
        mvc.perform(get(path(c)).with(jwt(UUID.randomUUID()))).andExpect(status().isForbidden)
    }

    @Test fun `adjacent versions preserve old input and replay while overlaps gaps and invalid intervals are explicit`() {
        val c = command()
        val first = service.register(c)
        val second =
            service.register(
                c.copy(
                    key = "terms-next-key",
                    sourceReference = "next-contract",
                    expectedRevision = 1,
                    effectiveFrom = requireNotNull(c.effectiveTo),
                    effectiveTo = null,
                    feeRateBps = 375,
                ),
            )
        assertThat(service.register(c)).isEqualTo(first)
        assertThat(
            tx.execute {
                source.findApplicable(
                    c.storeId,
                    c.effectiveFrom,
                )
            },
        ).isEqualTo(first.terms)
        assertThat(
            tx.execute {
                source.findApplicable(
                    c.storeId,
                    requireNotNull(c.effectiveTo),
                )
            },
        ).isEqualTo(second.terms)
        failure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE) {
            tx.execute {
                source.findApplicable(
                    c.storeId,
                    c.effectiveFrom.minusSeconds(1),
                )
            }
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.register(
                c.copy(
                    key = "terms-overlap-key",
                    sourceReference = "overlap-contract",
                    expectedRevision = 2,
                ),
            )
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.register(c.copy(key = "terms-stale-key"))
        }
        failure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
            service.register(c.copy(feeRateBps = 100))
        }
        failure(FailureCode.INVALID_REQUEST) {
            service.register(
                c.copy(
                    key = "terms-invalid-key",
                    feeRateBps = -1,
                ),
            )
        }
        failure(FailureCode.INVALID_REQUEST) {
            service.register(
                c.copy(
                    key = "terms-past-key",
                    effectiveFrom = c.now.minusSeconds(1),
                ),
            )
        }
        failure(FailureCode.INVALID_REQUEST) {
            service.register(c.copy(effectiveTo = c.effectiveFrom))
        }
        assertThatThrownBy {
            jdbc.update(
                "UPDATE merchant_store_settlement_terms SET fee_rate_bps = 1 WHERE terms_version_id = ?",
                first.terms.termsVersionId,
            )
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("merchant_store_settlement_terms")).isEqualTo(2)
        assertThat(count("operations_audit_record")).isEqualTo(2)
    }

    @Test fun `audit failure rolls back the terms version and replay ledger and revoked grants deny replay`() {
        val c = command()
        jdbc.execute(
            "ALTER TABLE operations_audit_record ADD CONSTRAINT test_terms_audit " +
                "CHECK (action <> 'STORE_SETTLEMENT_TERMS_REGISTERED')",
        )
        try {
            assertThatThrownBy {
                service.register(c)
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_terms_audit")
        }
        assertThat(count("merchant_terms_command")).isZero()
        assertThat(count("merchant_store_settlement_terms")).isZero()
        service.register(c)
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?",
            c.actorId,
        )
        failure(FailureCode.ACCESS_DENIED) {
            service.register(c)
        }
    }

    @Test fun `cursor and detail cannot cross actor or store scope and duplicate source is a conflict`() {
        val c = command()
        val first = service.register(c)
        service.register(
            c.copy(
                key = "terms-next-key",
                sourceReference = "next-contract",
                expectedRevision = 1,
                effectiveFrom = requireNotNull(c.effectiveTo),
                effectiveTo = null,
            ),
        )
        val page =
            service.list(
                c.actorId,
                c.storeId,
                null,
                1,
            )
        assertThat(page.nextCursor).isNotNull()
        assertThat(
            service
                .list(
                    c.actorId,
                    c.storeId,
                    page.nextCursor,
                    1,
                ).items,
        ).hasSize(1)
        val other = command()
        assertThatThrownBy {
            service.list(
                other.actorId,
                c.storeId,
                page.nextCursor,
                1,
            )
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            service.list(
                c.actorId,
                other.storeId,
                page.nextCursor,
                1,
            )
        }.isInstanceOf(DomainFailure::class.java)
        failure(FailureCode.RESOURCE_NOT_FOUND) {
            service.get(
                c.actorId,
                other.storeId,
                first.terms.termsVersionId,
            )
        }
        failure(FailureCode.RESOURCE_STATE_CONFLICT) {
            service.register(other.copy(sourceReference = c.sourceReference))
        }
    }

    @Test fun `terms cannot become effective while registration waits for the Store lock`() {
        val c = command()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerPid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    tx.execute {
                        jdbc.queryForObject("SELECT id FROM merchant_store WHERE id = ? FOR SHARE", UUID::class.java, c.storeId)
                        locked.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            check(locked.await(5, TimeUnit.SECONDS))
            val writer =
                pool.submit<FailureCode?> {
                    try {
                        tx.execute {
                            writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                            service.register(c)
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
            clock.set(c.effectiveFrom.plusSeconds(1))
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(FailureCode.INVALID_REQUEST)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM merchant_store_settlement_terms WHERE store_id = ?", Long::class.java, c.storeId),
            ).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_terms_command", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
            clock.set(c.now)
            val registered = service.register(c)
            clock.set(c.effectiveFrom.plusSeconds(1))
            assertThat(service.register(c)).isEqualTo(registered)
        } finally {
            release.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `concurrent registration has one winner and final order Store lock delays a writer`() {
        val c = command()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures =
                (0..1).map { index ->
                    executor.submit<Boolean> {
                        barrier.await(
                            5,
                            TimeUnit.SECONDS,
                        )
                        try {
                            service.register(
                                c.copy(
                                    key = "terms-race-key-$index",
                                    sourceReference = "race-contract-$index",
                                ),
                            )
                            true
                        } catch (e: DomainFailure) {
                            assertThat(e.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                            false
                        }
                    }
                }
            assertThat(
                futures.map {
                    it.get(
                        20,
                        TimeUnit.SECONDS,
                    )
                },
            ).containsExactlyInAnyOrder(
                true,
                false,
            )
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                executor.submit {
                    tx.execute {
                        jdbc.queryForObject(
                            "SELECT id FROM merchant_store WHERE id = ? FOR SHARE",
                            UUID::class.java,
                            c.storeId,
                        )
                        locked.countDown()
                        check(
                            release.await(
                                10,
                                TimeUnit.SECONDS,
                            ),
                        )
                    }
                }
            assertThat(
                locked.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
            val writer =
                executor.submit<ManagedStoreSettlementTerms> {
                    service.register(
                        c.copy(
                            key = "terms-order-lock-key",
                            sourceReference = "after-order-contract",
                            expectedRevision = 1,
                            effectiveFrom = requireNotNull(c.effectiveTo),
                            effectiveTo = null,
                        ),
                    )
                }
            try {
                assertThatThrownBy {
                    writer.get(
                        200,
                        TimeUnit.MILLISECONDS,
                    )
                }.isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            } finally {
                release.countDown()
            }
            holder.get(
                10,
                TimeUnit.SECONDS,
            )
            assertThat(
                writer
                    .get(
                        10,
                        TimeUnit.SECONDS,
                    ).revision,
            ).isEqualTo(2)
        } finally {
            executor.shutdownNow()
            assertThat(
                executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
    }

    private fun command(): RegisterStoreSettlementTermsCommand {
        val actor = UUID.randomUUID()
        val store = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store(id, accepting_orders, pickup_enabled, version) VALUES (?, false, false, 0)",
            store,
        )
        listOf(
            "STORE_SETTLEMENT_TERMS_READ",
            "STORE_SETTLEMENT_TERMS_WRITE",
        ).forEach { permission ->
            jdbc.update(
                "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, " +
                    "granted_at, version, audit_source_reference) " +
                    "VALUES (?, ?, 'ACTIVE', ?, 1, ?)",
                actor,
                permission,
                Timestamp.from(Instant.now().minusSeconds(1)),
                "terms:$actor:$permission",
            )
        }
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        return RegisterStoreSettlementTermsCommand(
            actor,
            store,
            "terms-register-key",
            "contract:$store",
            250,
            now.plusSeconds(3600),
            now.plusSeconds(7200),
            0,
            "계약 확인",
            now,
        )
    }

    private fun path(c: RegisterStoreSettlementTermsCommand) = "/api/v1/operations/stores/${c.storeId}/settlement-terms"

    private fun count(table: String) =
        jdbc.queryForObject(
            "SELECT count(*) FROM $table",
            Long::class.java,
        )

    private fun jwt(actor: UUID) =
        jwt()
            .jwt {
                it.subject(actor.toString())
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun failure(
        code: FailureCode,
        action: () -> Unit,
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(code)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class TermsManagementClockConfiguration {
    @Bean @Primary
    fun termsManagementClock() = TermsManagementTestClock()
}

internal class TermsManagementTestClock : Clock() {
    private val now = AtomicReference(Instant.now())

    fun set(value: Instant) {
        now.set(value)
    }

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
