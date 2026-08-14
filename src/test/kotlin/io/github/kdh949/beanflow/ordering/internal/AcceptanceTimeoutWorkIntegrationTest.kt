package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.acceptance-timeout-work.initial-delay-ms=3600000",
        "beanflow.acceptance-timeout-work.retention-initial-delay-ms=3600000",
        "beanflow.ordering-idempotency-retention.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class AcceptanceTimeoutWorkIntegrationTest
    @Autowired
    constructor(
        private val jdbcTemplate: JdbcTemplate,
        private val workService: AcceptanceTimeoutWorkService,
        private val works: AcceptanceTimeoutWorkJpaRepository,
        private val storeRetention: StoreCommandIdempotencyRetentionService,
        private val cancellationRetention: CancellationCommandIdempotencyRetentionService,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
        }

        @Test
        fun `four transient failures exhaust into one manual review case`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val orderId = insertOrder(fixture, "PAID", now.minusSeconds(1))
            val workId = insertWork(orderId, now.minusSeconds(1), now)
            var attemptAt = now

            repeat(4) { attemptIndex ->
                val claim = requireNotNull(workService.claim(workId, attemptAt))
                assertThat(claim.attemptCount).isEqualTo(attemptIndex + 1)
                val result =
                    requireNotNull(
                        workService.recordFailure(
                            claim,
                            DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "transient dependency failure"),
                            attemptAt,
                        ),
                    )
                if (attemptIndex < 3) {
                    assertThat(result.state).isEqualTo(AcceptanceTimeoutWorkState.PENDING)
                    attemptAt = requireNotNull(works.findById(workId).orElseThrow().nextAttemptAt)
                } else {
                    assertThat(result.state).isEqualTo(AcceptanceTimeoutWorkState.MANUAL_REVIEW)
                }
            }

            val exhausted = works.findById(workId).orElseThrow()
            assertThat(exhausted.state).isEqualTo(AcceptanceTimeoutWorkState.MANUAL_REVIEW)
            assertThat(exhausted.attemptCount).isEqualTo(4)
            assertThat(exhausted.lastFailureCode).isEqualTo("DEPENDENCY_UNAVAILABLE")
            assertThat(exhausted.claimToken).isNull()
            assertThat(exhausted.nextAttemptAt).isNull()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_reprocessing_case WHERE case_type = 'ACCEPTANCE_TIMEOUT_WORK'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(workService.purgeCompleted(now.plus(Duration.ofDays(365)), 100)).isZero()
        }

        @Test
        fun `expired fourth claim lease becomes manual review without a fifth attempt`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val orderId = insertOrder(fixture, "PAID", now.minusSeconds(1))
            val workId = insertWork(orderId, now.minusSeconds(1), now)
            var claimAt = now

            repeat(4) { attemptIndex ->
                val claim = requireNotNull(workService.claim(workId, claimAt))
                assertThat(claim.attemptCount).isEqualTo(attemptIndex + 1)
                claimAt = claimAt.plusSeconds(60)
            }

            assertThat(workService.claim(workId, claimAt)).isNull()
            val exhausted = works.findById(workId).orElseThrow()
            assertThat(exhausted.state).isEqualTo(AcceptanceTimeoutWorkState.MANUAL_REVIEW)
            assertThat(exhausted.attemptCount).isEqualTo(4)
            assertThat(exhausted.lastFailureCode).isEqualTo("CLAIM_LEASE_EXPIRED")
        }

        @Test
        fun `accepted before deadline completes not applicable and purges exactly after ninety days`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val deadline = now.minusSeconds(1)
            val orderId = insertOrder(fixture, "ACCEPTED", deadline)
            val workId = insertWork(orderId, deadline, now)
            val claim = requireNotNull(workService.claim(workId, now))

            assertThat(workService.classifySource(claim)).isEqualTo(AcceptanceTimeoutSourceOutcome.NOT_APPLICABLE)
            assertThat(
                workService.complete(
                    claim,
                    AcceptanceTimeoutCompletionOutcome.NOT_APPLICABLE,
                    now,
                ),
            ).isTrue()
            val completed = works.findById(workId).orElseThrow()
            assertThat(completed.state).isEqualTo(AcceptanceTimeoutWorkState.COMPLETED)
            assertThat(completed.completionOutcome).isEqualTo(AcceptanceTimeoutCompletionOutcome.NOT_APPLICABLE)
            assertThat(completed.retentionExpiresAt).isEqualTo(now.plus(Duration.ofDays(90)))

            assertThat(workService.purgeCompleted(now.plus(Duration.ofDays(90)).minus(1, ChronoUnit.MICROS), 100)).isZero()
            assertThat(workService.purgeCompleted(now.plus(Duration.ofDays(90)), 100)).isEqualTo(1)
            assertThat(works.existsById(workId)).isFalse()
        }

        @Test
        fun `different rejection source is not disguised as timeout success`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val deadline = now.minusSeconds(1)
            val orderId = insertOrder(fixture, "REJECTED", deadline)
            val workId = insertWork(orderId, deadline, now)
            val claim = requireNotNull(workService.claim(workId, now))

            assertThat(workService.classifySource(claim)).isEqualTo(AcceptanceTimeoutSourceOutcome.SOURCE_CONFLICT)
            assertThat(workService.sourceConflict(claim, now)).isTrue()
            assertThat(works.findById(workId).orElseThrow().state).isEqualTo(AcceptanceTimeoutWorkState.MANUAL_REVIEW)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT reason FROM operations_reprocessing_case WHERE case_type = 'ACCEPTANCE_TIMEOUT_WORK'",
                    String::class.java,
                ),
            ).isEqualTo("TIMEOUT_SOURCE_CONFLICT")
        }

        @Test
        fun `idempotency retention deletes at most one hundred rows per table and tick`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val orderId = insertOrder(fixture, "PAID", now.minusSeconds(1))
            val createdAt = now.minus(Duration.ofDays(91))
            repeat(101) { index ->
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_store_command_idempotency (
                        id, actor_id, order_id, operation, idempotency_key, payload_hash,
                        response_status, response_body, created_at, retention_expires_at
                    ) VALUES (?, ?, ?, 'STORE_ORDER_TRANSITION_V2', ?, ?, 200, '{}', ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    fixture.customerId,
                    orderId,
                    "store-key-${index.toString().padStart(3, '0')}",
                    "a".repeat(64),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plus(Duration.ofDays(90))),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_cancellation_command_idempotency (
                        id, actor_id, order_id, operation, idempotency_key, payload_hash,
                        response_status, response_body, response_version, created_at, retention_expires_at
                    ) VALUES (?, ?, ?, 'CUSTOMER_ORDER_CANCELLATION', ?, ?, 200, '{}', 1, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    fixture.customerId,
                    orderId,
                    "cancel-key-${index.toString().padStart(3, '0')}",
                    "b".repeat(64),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plus(Duration.ofDays(90))),
                )
            }

            val storeFirst = storeRetention.purgeDue(now, 100)
            val cancellationFirst = cancellationRetention.purgeDue(now, 100)
            assertThat(storeFirst.deletedCount).isEqualTo(100)
            assertThat(storeFirst.remainingBacklog).isEqualTo(1)
            assertThat(cancellationFirst.deletedCount).isEqualTo(100)
            assertThat(cancellationFirst.remainingBacklog).isEqualTo(1)
            assertThat(storeRetention.purgeDue(now, 100).deletedCount).isEqualTo(1)
            assertThat(cancellationRetention.purgeDue(now, 100).deletedCount).isEqualTo(1)
        }

        private fun insertOrder(
            fixture: OrderCreationFixture,
            state: String,
            deadline: Instant,
        ): UUID {
            val orderId = UUID.randomUUID()
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                val paidAt = deadline.minusSeconds(3 * 60)
                val acceptedAt = if (state == "ACCEPTED") deadline.minus(1, ChronoUnit.MICROS) else null
                val rejectedAt = if (state == "REJECTED") deadline.plus(1, ChronoUnit.MICROS) else null
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, accepted_at, rejected_at, rejection_reason,
                        created_at, updated_at, version
                    ) VALUES (
                        ?, ?, ?, ?, ?, DATE '2030-01-01', 1,
                        'BeanFlow Test Store', ?, ?,
                        ?, 1000, 0, 0, 1000, 'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, 0
                    )
                    """.trimIndent(),
                    orderId,
                    fixture.customerId,
                    fixture.storeId,
                    fixture.pickupSlotId,
                    publicReference,
                    Timestamp.from(Instant.parse("2030-01-01T00:10:00Z")),
                    Timestamp.from(Instant.parse("2030-01-01T00:20:00Z")),
                    state,
                    Timestamp.from(paidAt),
                    Timestamp.from(paidAt.plusSeconds(2 * 60)),
                    Timestamp.from(deadline),
                    acceptedAt?.let(Timestamp::from),
                    rejectedAt?.let(Timestamp::from),
                    if (state == "REJECTED") "MANUAL_REJECTION" else null,
                    Timestamp.from(paidAt.minusSeconds(1)),
                    Timestamp.from(nowForState(acceptedAt, rejectedAt, paidAt)),
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
            return orderId
        }

        private fun nowForState(
            acceptedAt: Instant?,
            rejectedAt: Instant?,
            paidAt: Instant,
        ): Instant = acceptedAt ?: rejectedAt ?: paidAt

        private fun insertWork(
            orderId: UUID,
            deadline: Instant,
            now: Instant,
        ): UUID =
            UUID.randomUUID().also { workId ->
                works.saveAndFlush(
                    AcceptanceTimeoutWorkEntity(
                        id = workId,
                        orderId = orderId,
                        acceptanceDeadlineAt = deadline,
                        state = AcceptanceTimeoutWorkState.PENDING,
                        sourceReference = "order:$orderId:acceptance-timeout:$deadline",
                        createdAt = now,
                        updatedAt = now,
                        nextAttemptAt = now,
                    ),
                )
            }
    }
