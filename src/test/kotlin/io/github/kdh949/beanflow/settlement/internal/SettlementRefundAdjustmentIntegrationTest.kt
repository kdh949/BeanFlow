package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.eventing.api.SettlementRefundEffect
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Import(TestcontainersConfiguration::class)
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
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SettlementRefundAdjustmentIntegrationTest
    @Autowired
    constructor(
        private val service: SettlementRefundAdjustmentService,
        private val batchLifecycle: SettlementBatchLifecycleService,
        private val eventPublisher: ApplicationEventPublisher,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_dispute,
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication,
                    payment_refund,
                    payment_payment,
                    payment_method,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `confirmed completed refund creates one immutable Adjustment Audit and event across replay`() {
            val fixture = confirmedFixture()

            adjust(fixture.event)
            adjust(fixture.event.copy(envelope = fixture.event.envelope.copy(eventId = UUID.randomUUID())))

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(value<Long>("SELECT amount_krw FROM settlement_adjustment")).isEqualTo(-570)
            assertThat(value<String>("SELECT reason_code FROM settlement_adjustment")).isEqualTo("REFUND_SUCCEEDED")
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_ADJUSTMENT_CREATED'"))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementAdjustmentCreatedV1",
                ),
            ).isOne()
        }

        @Test
        fun `persistent completed refund listener creates Adjustment only after source transaction commits`() {
            val fixture = confirmedFixture()

            transactions.executeWithoutResult { eventPublisher.publishEvent(fixture.event) }

            await("completed refund adjustment") {
                count("SELECT count(*) FROM settlement_adjustment") == 1L
            }
            assertThat(value<Long>("SELECT amount_krw FROM settlement_adjustment")).isEqualTo(-570)
        }

        @Test
        fun `unconfirmed source stays retryable without Adjustment Audit or manual fallback`() {
            val fixture = itemFixture()

            assertThatThrownBy { adjust(fixture.event) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE)
                    assertThat(it.message).contains("confirmed SettlementBatch")
                }

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_ADJUSTMENT_CREATED'"))
                .isZero()
            assertThat(count("SELECT count(*) FROM operations_reprocessing_case")).isZero()
        }

        @Test
        fun `source replay with a different amount opens source-unique manual review`() {
            val fixture = confirmedFixture()
            adjust(fixture.event)
            val conflict =
                fixture.event.copy(
                    settlementRefundEffect =
                        SettlementRefundEffect(
                            grossPaidDeltaKrw = -400,
                            feeDeltaKrw = -20,
                            benefitCostDeltaKrw = 0,
                            netSettlementDeltaKrw = -380,
                        ),
                )

            assertThatThrownBy { adjust(conflict) }
                .isInstanceOf(DomainFailure::class.java)
                .hasMessageContaining("ADJUSTMENT_SOURCE_CONFLICT")

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'SETTLEMENT_ADJUSTMENT' AND status = 'MANUAL_REVIEW'",
                ),
            ).isOne()
        }

        @Test
        fun `pre-completion refund is explicit manual review and never an Adjustment`() {
            val event = preCompletionEvent()

            assertThatThrownBy {
                transactions.executeWithoutResult { service.deferPreCompletion(event, PROCESSED_AT) }
            }.isInstanceOf(DomainFailure::class.java)

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(
                value<String>(
                    "SELECT reason FROM operations_reprocessing_case WHERE case_type = 'SETTLEMENT_ADJUSTMENT'",
                ),
            ).isEqualTo("PRE_COMPLETION_REFUND_REQUIRES_SETTLEMENT_RECONCILIATION")
        }

        @Test
        fun `publication failure rolls back Adjustment and Audit then retry succeeds`() {
            val fixture = confirmedFixture()
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_adjustment " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementAdjustmentCreatedV1')",
            )
            try {
                assertThatThrownBy { adjust(fixture.event) }.isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_reject_adjustment")
            }
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_ADJUSTMENT_CREATED'"))
                .isZero()

            adjust(fixture.event)
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
        }

        @Test
        fun `failed Refund state without success event produces zero Adjustment`() {
            val storeId = insertStore()
            val orderId = insertCompletedOrder(storeId, COMPLETED_AT)
            val paymentId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO payment_payment (
                    id, order_id, type, approval_state, approved_amount_krw,
                    requested_amount_krw, succeeded_refund_amount_krw, currency,
                    benefit_snapshot_reference, source_reference, correlation_id,
                    approved_at, created_at, updated_at, version
                ) VALUES (?, ?, 'BENEFIT_ONLY', 'APPROVED', 0, 0, 0, 'KRW', ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                paymentId,
                orderId,
                "benefit:$orderId",
                "payment:$orderId",
                "failed-refund-test",
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(COMPLETED_AT),
            )
            val refundId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO payment_refund (
                    id, payment_id, order_id, requested_amount_krw, succeeded_amount_krw,
                    reason, state, provider_idempotency_key, source_reference,
                    attempt_count, request_attempt_count, lookup_attempt_count,
                    next_action, created_at, updated_at, version
                ) VALUES (?, ?, ?, 600, NULL, 'CUSTOMER_REQUEST', 'FAILED', ?, ?, 1, 1, 0,
                          'REQUEST', ?, ?, 0)
                """.trimIndent(),
                refundId,
                paymentId,
                orderId,
                "failed-refund-key:$refundId",
                "failed-refund:$refundId",
                Timestamp.from(PROCESSED_AT),
                Timestamp.from(PROCESSED_AT),
            )

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1",
                ),
            ).isZero()
        }

        private fun confirmedFixture(): Fixture =
            itemFixture().also {
                batchLifecycle.calculate(it.batchId, Instant.parse("2026-08-02T00:00:00Z"))
                batchLifecycle.confirm(it.batchId, Instant.parse("2026-08-02T00:01:00Z"), "batch-confirm")
            }

        private fun itemFixture(): Fixture {
            val storeId = insertStore()
            val orderId = insertCompletedOrder(storeId, COMPLETED_AT)
            val batchId = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            val source = "order:$orderId:completed:7"
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
                source,
                Timestamp.from(COMPLETED_AT),
                SETTLEMENT_DATE,
                Timestamp.from(COMPLETED_AT),
            )
            val refundId = UUID.randomUUID()
            return Fixture(
                batchId = batchId,
                event =
                    PaymentRefundedV1(
                        envelope =
                            EventEnvelope(
                                eventId = UUID.randomUUID(),
                                eventType = "PaymentRefundedV1",
                                aggregateId = refundId,
                                aggregateVersion = 1,
                                occurredAt = REFUND_SUCCEEDED_AT,
                                payloadVersion = 1,
                                correlationId = "refund-correlation",
                                causationId = "refund:$refundId:succeeded",
                            ),
                        refundId = refundId,
                        refundSource = "refund:$refundId",
                        orderId = orderId,
                        customerId = UUID.randomUUID(),
                        refundSucceededAt = REFUND_SUCCEEDED_AT,
                        currency = "KRW",
                        cashRefundedKrw = 600,
                        completionDisposition = RefundCompletionDisposition.COMPLETED_ORDER,
                        orderCompletedAt = COMPLETED_AT,
                        settlementDate = SETTLEMENT_DATE,
                        settlementItemSource = source,
                        settlementRefundEffect =
                            SettlementRefundEffect(
                                grossPaidDeltaKrw = -600,
                                feeDeltaKrw = -30,
                                benefitCostDeltaKrw = 0,
                                netSettlementDeltaKrw = -570,
                            ),
                    ),
            )
        }

        private fun preCompletionEvent(): PaymentRefundedV1 {
            val refundId = UUID.randomUUID()
            return PaymentRefundedV1(
                envelope =
                    EventEnvelope(
                        eventId = UUID.randomUUID(),
                        eventType = "PaymentRefundedV1",
                        aggregateId = refundId,
                        aggregateVersion = 1,
                        occurredAt = REFUND_SUCCEEDED_AT,
                        payloadVersion = 1,
                        correlationId = "pre-completion-refund",
                        causationId = "refund:$refundId:succeeded",
                    ),
                refundId = refundId,
                refundSource = "refund:$refundId",
                orderId = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                refundSucceededAt = REFUND_SUCCEEDED_AT,
                currency = "KRW",
                cashRefundedKrw = 600,
                completionDisposition = RefundCompletionDisposition.PRE_COMPLETION_ORDER,
                settlementRefundEffect =
                    SettlementRefundEffect(
                        grossPaidDeltaKrw = -600,
                        feeDeltaKrw = -30,
                        benefitCostDeltaKrw = 0,
                        netSettlementDeltaKrw = -570,
                    ),
            )
        }

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertCompletedOrder(
            storeId: UUID,
            completedAt: Instant,
        ): UUID =
            UUID.randomUUID().also { orderId ->
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id, state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, 'COMPLETED', 1000, 0, 0, 1000,
                                  'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        Timestamp.from(completedAt.minusSeconds(180)),
                        Timestamp.from(completedAt.minusSeconds(120)),
                        Timestamp.from(completedAt.minusSeconds(60)),
                        Timestamp.from(completedAt.minusSeconds(150)),
                        Timestamp.from(completedAt.minusSeconds(90)),
                        Timestamp.from(completedAt.minusSeconds(30)),
                        Timestamp.from(completedAt),
                        Timestamp.from(completedAt.minusSeconds(300)),
                        Timestamp.from(completedAt),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun adjust(event: PaymentRefundedV1) {
            transactions.executeWithoutResult { service.adjust(event, PROCESSED_AT) }
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
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runCatching(assertion).getOrDefault(false)) return
                Thread.sleep(20)
            }
            check(assertion()) { "Timed out waiting for $description" }
        }

        private data class Fixture(
            val batchId: UUID,
            val event: PaymentRefundedV1,
        )

        private companion object {
            val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
            val COMPLETED_AT: Instant = Instant.parse("2026-08-01T01:00:00Z")
            val SETTLEMENT_DATE: LocalDate = COMPLETED_AT.atZone(SEOUL).toLocalDate()
            val REFUND_SUCCEEDED_AT: Instant = Instant.parse("2026-08-03T01:00:00Z")
            val PROCESSED_AT: Instant = Instant.parse("2026-08-03T01:01:00Z")
        }
    }
