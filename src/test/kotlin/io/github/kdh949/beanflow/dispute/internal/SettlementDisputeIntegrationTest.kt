package io.github.kdh949.beanflow.dispute.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.ordering.internal.EventPublicationRecoveryWorker
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchLifecycleService
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, SettlementDisputeTestConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.point-recovery.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.settlement.batch.initial-delay-ms=3600000",
        "beanflow.settlement.dispute.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SettlementDisputeIntegrationTest
    @Autowired
    constructor(
        private val filing: SettlementDisputeFilingService,
        private val decisions: SettlementDisputeDecisionService,
        private val batchLifecycle: SettlementBatchLifecycleService,
        private val clock: SettlementDisputeTestClock,
        private val publicationRecovery: EventPublicationRecoveryWorker,
        private val jdbcTemplate: JdbcTemplate,
        private val mockMvc: MockMvc,
    ) {
        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_dispute,
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    identity_store_membership,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            clock.set(WINDOW_OPEN)
        }

        @Test
        fun `filing persists held Audit event and replays the exact terminal response`() {
            val fixture = fixture()

            val created = file(fixture, "dispute-key-0001")
            val replay = file(fixture, "dispute-key-0001")

            assertThat(replay).isEqualTo(created)
            assertThat(created.state).isEqualTo(SettlementDisputeState.FILED)
            assertThat(created.heldAmountKrw).isEqualTo(-120)
            assertThat(count("SELECT count(*) FROM settlement_dispute")).isOne()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_FILED'"))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1",
                ),
            ).isOne()
            val serialized =
                value<String>(
                    "SELECT serialized_event FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1",
                )
            assertThat(serialized).contains("\"state\":\"FILED\"")
            assertThat(serialized).doesNotContain("evidence:first", fixture.actorId.toString(), "dispute-key-0001")
            clock.set(WINDOW_OPEN.plusSeconds(11))
            publicationRecovery.runOnce()
            await("Dispute filed Operations publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.operations.settlement-dispute-filed-v1",
                ) == 1L
            }

            assertThatThrownBy { file(fixture, "dispute-key-0001", amountKrw = -121) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                }
        }

        @Test
        fun `Batch confirmation publication completes after Dispute validates the confirmed public view`() {
            val fixture = fixture()

            publicationRecovery.runOnce()

            await("Batch confirmation publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.dispute.settlement-batch-confirmed-v1",
                ) == 1L
            }
            assertThat(fixture.itemId).isNotEqualTo(UUID(0, 0))
        }

        @Test
        fun `D plus one opens inclusively and D plus fifteen closes exclusively`() {
            val fixture = fixture()

            clock.set(WINDOW_OPEN.minusNanos(1))
            assertWindowClosed { file(fixture, "dispute-key-before") }

            clock.set(WINDOW_CLOSE)
            assertWindowClosed { file(fixture, "dispute-key-close") }

            clock.set(WINDOW_OPEN)
            assertThat(file(fixture, "dispute-key-open").state).isEqualTo(SettlementDisputeState.FILED)
        }

        @Test
        fun `concurrent different keys for one Item converge to one active dispute`() {
            val fixture = fixture()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf("concurrent-key-01", "concurrent-key-02").map { key ->
                        executor.submit<Any> {
                            barrier.await(5, TimeUnit.SECONDS)
                            try {
                                file(fixture, key)
                            } catch (failure: DomainFailure) {
                                failure
                            }
                        }
                    }
                val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(outcomes.count { it is SettlementDisputeResponse }).isOne()
                assertThat(
                    outcomes.filterIsInstance<DomainFailure>().single().code,
                ).isEqualTo(FailureCode.DISPUTE_ALREADY_ACTIVE)
            } finally {
                executor.shutdownNow()
            }
            assertThat(count("SELECT count(*) FROM settlement_dispute WHERE state = 'FILED'")).isOne()
        }

        @Test
        fun `filed publication failure rolls back Dispute held and Audit then retry succeeds`() {
            val fixture = fixture()
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_dispute_filing " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1')",
            )
            try {
                assertThatThrownBy { file(fixture, "filing-failure-key") }
                    .isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_reject_dispute_filing")
            }
            assertThat(count("SELECT count(*) FROM settlement_dispute")).isZero()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_FILED'"))
                .isZero()

            assertThat(file(fixture, "filing-failure-key").state).isEqualTo(SettlementDisputeState.FILED)
        }

        @Test
        fun `revoked and staff membership cannot file an owner dispute`() {
            val fixture = fixture()
            jdbcTemplate.update(
                "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ? AND store_id = ?",
                fixture.actorId,
                fixture.storeId,
            )
            assertAccessDenied { file(fixture, "revoked-owner-key") }

            jdbcTemplate.update(
                "UPDATE identity_store_membership SET status = 'ACTIVE', membership_role = 'STAFF' " +
                    "WHERE actor_id = ? AND store_id = ?",
                fixture.actorId,
                fixture.storeId,
            )
            assertAccessDenied {
                filing.file(command(fixture, "staff-member-key", roles = setOf(StoreActorRole.STAFF)))
            }
        }

        @Test
        fun `terminal dispute permits one refile only with a new evidence reference`() {
            val fixture = fixture()
            val first = file(fixture, "first-dispute-key")
            decisions.startReview(first.disputeId)
            decisions.reject(first.disputeId, DECIDED_AT)

            assertRefilingRejected { file(fixture, "missing-previous-key") }
            assertRefilingRejected {
                file(
                    fixture,
                    "same-evidence-key",
                    evidence = listOf("evidence:first"),
                    previousDisputeId = first.disputeId,
                )
            }
            val refile =
                file(
                    fixture,
                    "valid-refile-key",
                    evidence = listOf("evidence:first", "evidence:new"),
                    previousDisputeId = first.disputeId,
                )
            decisions.startReview(refile.disputeId)
            decisions.withdraw(refile.disputeId, DECIDED_AT.plusSeconds(1))

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM settlement_dispute WHERE state IN ('REJECTED', 'WITHDRAWN') " +
                        "AND held_amount_krw = 0",
                ),
            ).isEqualTo(2)

            assertRefilingRejected {
                file(
                    fixture,
                    "second-refile-key",
                    evidence = listOf("evidence:third"),
                    previousDisputeId = refile.disputeId,
                )
            }
        }

        @Test
        fun `accepted decision waits for Adjustment and recovers after decision publication failure`() {
            val fixture = fixture()
            val dispute = file(fixture, "accepted-dispute-key")
            decisions.startReview(dispute.disputeId)
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_dispute_decision " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1')",
            )
            try {
                assertThatThrownBy { decisions.accept(dispute.disputeId, DECIDED_AT) }
                    .isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_reject_dispute_decision")
            }

            assertThat(value<String>("SELECT state FROM settlement_dispute WHERE id = ?", dispute.disputeId))
                .isEqualTo("UNDER_REVIEW")
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(
                value<String>(
                    "SELECT status FROM operations_reprocessing_case WHERE case_type = 'SETTLEMENT_DISPUTE'",
                ),
            ).isEqualTo("MANUAL_REVIEW")

            val accepted = decisions.accept(dispute.disputeId, DECIDED_AT)

            assertThat(accepted.state).isEqualTo(SettlementDisputeState.ACCEPTED)
            assertThat(accepted.heldAmountKrw).isZero()
            assertThat(accepted.settlementAdjustmentId).isNotNull()
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(
                value<String>(
                    "SELECT status FROM operations_reprocessing_case WHERE case_type = 'SETTLEMENT_DISPUTE'",
                ),
            ).isEqualTo("RESOLVED")
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_DECIDED'"))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1",
                ),
            ).isOne()
            val serialized =
                value<String>(
                    "SELECT serialized_event FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1",
                )
            assertThat(serialized).doesNotContain("evidence:first", fixture.actorId.toString(), "accepted-dispute-key")
            clock.set(DECIDED_AT.plusSeconds(11))
            publicationRecovery.runOnce()
            await("Dispute decided Operations publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.operations.settlement-dispute-decided-v1",
                ) == 1L
            }
        }

        @Test
        fun `owner HTTP contract creates FILED response while staff role is forbidden`() {
            val fixture = fixture()
            val body =
                """
                {
                  "expectedAdjustmentKrw": -120,
                  "reason": "fee mismatch",
                  "evidenceReferences": ["evidence:http"]
                }
                """.trimIndent()

            mockMvc
                .perform(
                    post("/api/v1/settlement-items/${fixture.itemId}/disputes")
                        .with(ownerJwt(fixture.actorId))
                        .header("Idempotency-Key", "http-dispute-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.settlementItemId").value(fixture.itemId.toString()))
                .andExpect(jsonPath("$.state").value("FILED"))
                .andExpect(jsonPath("$.heldAmountKrw").value(-120))

            mockMvc
                .perform(
                    post("/api/v1/settlement-items/${fixture.itemId}/disputes")
                        .with(staffJwt(UUID.randomUUID()))
                        .header("Idempotency-Key", "staff-http-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
        }

        private fun fixture(): Fixture {
            val storeId = insertStore()
            val actorId = UUID.randomUUID()
            insertMembership(actorId, storeId)
            val orderId = insertCompletedOrder(storeId)
            val batchId = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                    "VALUES (?, ?, ?, 'OPEN', ?, 0)",
                batchId,
                storeId,
                SETTLEMENT_DATE,
                Timestamp.from(COMPLETED_AT),
            )
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'KRW', 1000, 500, 50, 25, 25, 50, 900, ?)
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
                Timestamp.from(COMPLETED_AT),
                SETTLEMENT_DATE,
                Timestamp.from(COMPLETED_AT),
            )
            batchLifecycle.calculate(batchId, CALCULATED_AT)
            batchLifecycle.confirm(batchId, CONFIRMED_AT, "dispute-fixture-confirmation")
            return Fixture(actorId, storeId, itemId)
        }

        private fun file(
            fixture: Fixture,
            key: String,
            amountKrw: Long = -120,
            evidence: List<String> = listOf("evidence:first"),
            previousDisputeId: UUID? = null,
        ): SettlementDisputeResponse = filing.file(command(fixture, key, amountKrw, evidence, previousDisputeId))

        private fun command(
            fixture: Fixture,
            key: String,
            amountKrw: Long = -120,
            evidence: List<String> = listOf("evidence:first"),
            previousDisputeId: UUID? = null,
            roles: Set<StoreActorRole> = setOf(StoreActorRole.OWNER),
        ): FileSettlementDisputeCommand =
            FileSettlementDisputeCommand(
                actorId = fixture.actorId,
                actorRoles = roles,
                settlementItemId = fixture.itemId,
                idempotencyKey = key,
                expectedAdjustmentKrw = amountKrw,
                reason = "settlement amount mismatch",
                evidenceReferences = evidence,
                previousDisputeId = previousDisputeId,
                correlationId = "dispute-test-correlation",
            )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertMembership(
            actorId: UUID,
            storeId: UUID,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(COMPLETED_AT),
            )
        }

        private fun insertCompletedOrder(storeId: UUID): UUID =
            UUID.randomUUID().also { orderId ->
                val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id,
                            public_reference, pickup_business_date, pickup_sequence,
                            store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                            state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                                  'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                                  'COMPLETED', 1000, 0, 0, 1000,
                                  'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        publicReference,
                        OrderCreationDatabaseFixture.pickupSequence(orderId),
                        Timestamp.from(COMPLETED_AT.minusSeconds(180)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(120)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(60)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(150)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(90)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(30)),
                        Timestamp.from(COMPLETED_AT),
                        Timestamp.from(COMPLETED_AT.minusSeconds(300)),
                        Timestamp.from(COMPLETED_AT),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun ownerJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_OWNER")) }
                .authorities(SimpleGrantedAuthority("ROLE_STORE_OWNER"))

        private fun staffJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_STAFF")) }
                .authorities(SimpleGrantedAuthority("ROLE_STORE_STAFF"))

        private fun assertWindowClosed(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DISPUTE_WINDOW_CLOSED)
                }
        }

        private fun assertAccessDenied(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
                }
        }

        private fun assertRefilingRejected(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DISPUTE_REFILE_NOT_ALLOWED)
                }
        }

        private fun count(
            sql: String,
            vararg arguments: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *arguments))

        private inline fun <reified T : Any> value(
            sql: String,
            vararg arguments: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *arguments))

        private fun await(
            description: String,
            assertion: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runCatching(assertion).getOrDefault(false)) return
                Thread.sleep(20)
            }
            check(assertion()) { "Timed out waiting for $description" }
        }

        private data class Fixture(
            val actorId: UUID,
            val storeId: UUID,
            val itemId: UUID,
        )

        private companion object {
            val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
            val COMPLETED_AT: Instant = Instant.parse("2026-08-03T01:00:00Z")
            val SETTLEMENT_DATE: LocalDate = COMPLETED_AT.atZone(SEOUL).toLocalDate()
            val CALCULATED_AT: Instant = Instant.parse("2026-08-04T00:00:00Z")
            val CONFIRMED_AT: Instant = Instant.parse("2026-08-04T00:01:00Z")
            val WINDOW_OPEN: Instant = Instant.parse("2026-08-04T15:00:00Z")
            val WINDOW_CLOSE: Instant = Instant.parse("2026-08-18T15:00:00Z")
            val DECIDED_AT: Instant = Instant.parse("2026-08-06T00:00:00Z")
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class SettlementDisputeTestConfiguration {
    @Bean
    @Primary
    fun settlementDisputeTestClock(): SettlementDisputeTestClock = SettlementDisputeTestClock()
}

internal class SettlementDisputeTestClock : Clock() {
    private val current = AtomicReference(Instant.parse("2026-08-04T15:00:00Z"))

    fun set(now: Instant) {
        current.set(now)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()
}
