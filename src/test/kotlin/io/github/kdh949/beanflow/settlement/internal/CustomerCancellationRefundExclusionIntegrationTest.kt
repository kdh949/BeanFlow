package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.payment.internal.PaymentEntity
import io.github.kdh949.beanflow.payment.internal.PaymentJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodEntity
import io.github.kdh949.beanflow.payment.internal.PaymentMethodJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodStatus
import io.github.kdh949.beanflow.payment.internal.RefundEntity
import io.github.kdh949.beanflow.payment.internal.RefundJpaRepository
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
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
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class CustomerCancellationRefundExclusionIntegrationTest
    @Autowired
    constructor(
        private val service: CustomerCancellationRefundExclusionService,
        private val paymentMethods: PaymentMethodJpaRepository,
        private val payments: PaymentJpaRepository,
        private val refunds: RefundJpaRepository,
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
                    settlement_item,
                    settlement_batch,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication,
                    payment_cancellation_recovery_snapshot,
                    payment_refund,
                    payment_payment,
                    payment_method,
                    ordering_order
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `valid customer cancellation refund appends one exclusion audit across replay`() {
            val fixture = fixture()

            exclude(fixture.event)
            exclude(fixture.event.copy(envelope = fixture.event.envelope.copy(eventId = UUID.randomUUID())))

            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", fixture.orderId)).isZero()
            assertThat(
                count(
                    """
                    SELECT count(*)
                      FROM operations_audit_record
                     WHERE actor_type = 'SYSTEM'
                       AND action = 'SETTLEMENT_REFUND_EXCLUDED'
                       AND target_type = 'REFUND'
                       AND target_id = ?
                       AND reason = 'ORDER_NOT_COMPLETED_CUSTOMER_CANCELLATION'
                       AND source_reference = ?
                       AND before_summary = '{"settlementItemExists":"false"}'
                       AND after_summary = '{"settlementDisposition":"NOT_APPLICABLE"}'
                    """.trimIndent(),
                    fixture.refundId,
                    fixture.source,
                ),
            ).isOne()
        }

        @Test
        fun `missing payment setup opens durable evidence and keeps settlement exclusion retryable`() {
            val fixture = fixture()
            jdbcTemplate.update(
                "DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?",
                fixture.orderId,
            )

            assertConflict(fixture.event, "payment setup is incomplete")

            assertThat(exclusionAuditCount()).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP' AND status = 'OPEN'",
                ),
            ).isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                ),
            ).isOne()
        }

        @Test
        fun `settlement exclusion fails retryably when setup evidence cannot persist`() {
            val fixture = fixture()
            jdbcTemplate.update(
                "DELETE FROM payment_cancellation_recovery_snapshot WHERE order_id = ?",
                fixture.orderId,
            )
            jdbcTemplate.execute(
                "ALTER TABLE operations_audit_record ADD CONSTRAINT test_reject_settlement_setup_detection " +
                    "CHECK (action <> 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED')",
            )
            try {
                assertThatThrownBy { exclude(fixture.event) }
                    .isInstanceOfSatisfying(DomainFailure::class.java) {
                        assertThat(it.code).isEqualTo(io.github.kdh949.beanflow.shared.api.FailureCode.DEPENDENCY_UNAVAILABLE)
                    }
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE operations_audit_record DROP CONSTRAINT test_reject_settlement_setup_detection",
                )
            }

            assertThat(exclusionAuditCount()).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP'",
                ),
            ).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                ),
            ).isZero()
        }

        @Test
        fun `persistent publication completes only after exclusion audit commits`() {
            val fixture = fixture()

            transactions.executeWithoutResult { eventPublisher.publishEvent(fixture.event) }

            await("settlement publication and audit completion") {
                exclusionAuditCount() == 1L &&
                    count(
                        """
                        SELECT count(*)
                          FROM event_publication
                         WHERE listener_id = 'beanflow.settlement.payment-refunded-v1'
                           AND completion_date IS NOT NULL
                        """.trimIndent(),
                    ) == 1L
            }
            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", fixture.orderId)).isZero()
        }

        @Test
        fun `non-customer Order cause keeps the refund exclusion incomplete`() {
            val fixture = fixture()
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET cancellation_cause = 'PAYMENT_DECLINED',
                       cancellation_reason_code = NULL
                 WHERE id = ?
                """.trimIndent(),
                fixture.orderId,
            )

            assertConflict(fixture.event, "customer-request cancellation")
            assertThat(exclusionAuditCount()).isZero()
        }

        @Test
        fun `refund reason source and version mismatches are explicit conflicts`() {
            val wrongReason = fixture(refundReason = "STORE_ORDER_REJECTED")
            assertConflict(wrongReason.event, "Refund reason")

            cleanData()
            val wrongTerminalSource = fixture()
            assertConflict(
                wrongTerminalSource.event.copy(refundSource = "wrong-terminal-source"),
                "terminal Order version",
            )

            cleanData()
            val wrongSource = fixture(storedRefundSource = "wrong-source")
            assertConflict(wrongSource.event, "Refund source")

            cleanData()
            val wrongVersion = fixture()
            assertConflict(
                wrongVersion.event.copy(
                    envelope =
                        wrongVersion.event.envelope.copy(
                            aggregateVersion = wrongVersion.event.envelope.aggregateVersion + 1,
                        ),
                ),
                "Refund event version",
            )

            assertThat(exclusionAuditCount()).isZero()
        }

        @Test
        fun `unexpected existing item is not overwritten or treated as exclusion`() {
            val fixture = fixture()
            insertUnexpectedItem(fixture)

            assertConflict(fixture.event, "unexpectedly has a SettlementItem")

            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", fixture.orderId)).isOne()
            assertThat(exclusionAuditCount()).isZero()
        }

        @Test
        fun `audit insert failure rolls back exclusion and remains retryable`() {
            val fixture = fixture()
            jdbcTemplate.execute(
                """
                ALTER TABLE operations_audit_record
                ADD CONSTRAINT test_reject_refund_exclusion
                CHECK (action <> 'SETTLEMENT_REFUND_EXCLUDED')
                """.trimIndent(),
            )
            try {
                assertThatThrownBy { exclude(fixture.event) }.isInstanceOf(RuntimeException::class.java)
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE operations_audit_record DROP CONSTRAINT test_reject_refund_exclusion",
                )
            }

            assertThat(exclusionAuditCount()).isZero()
            exclude(fixture.event)
            assertThat(exclusionAuditCount()).isOne()
        }

        private fun fixture(
            refundReason: String = "CUSTOMER_ORDER_CANCELLED",
            storedRefundSource: String? = null,
        ): Fixture {
            val customerId = UUID.randomUUID()
            val storeId = insertStore()
            val orderId = UUID.randomUUID()
            val orderVersion = 7L
            val source = "order:$orderId:customer-cancellation:$orderVersion:payment"
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
                        acceptance_deadline_at, cancelled_at, cancellation_cause,
                        cancellation_reason_code,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                              'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                              'CANCELLED', 1000, 0, 0, 1000,
                              'KRW', NULL, ?, ?, ?, ?, 'CUSTOMER_REQUEST', 'OTHER', ?, ?, ?)
                    """.trimIndent(),
                    orderId,
                    customerId,
                    storeId,
                    UUID.randomUUID(),
                    publicReference,
                    OrderCreationDatabaseFixture.pickupSequence(orderId),
                    Timestamp.from(PAID_AT),
                    Timestamp.from(PAID_AT.plusSeconds(120)),
                    Timestamp.from(PAID_AT.plusSeconds(180)),
                    Timestamp.from(CANCELLED_AT),
                    Timestamp.from(CREATED_AT),
                    Timestamp.from(CANCELLED_AT),
                    orderVersion,
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }

            val paymentMethod =
                paymentMethods.saveAndFlush(
                    PaymentMethodEntity(
                        id = UUID.randomUUID(),
                        customerId = customerId,
                        provider = "TEST_PROVIDER",
                        tokenReference = "token:${UUID.randomUUID()}",
                        displayAlias = "test card",
                        cardBrand = "TEST",
                        lastFour = "4242",
                        status = PaymentMethodStatus.ACTIVE,
                        createdAt = CREATED_AT,
                        updatedAt = CREATED_AT,
                    ),
                )
            val payment =
                payments.saveAndFlush(
                    PaymentEntity(
                        id = UUID.randomUUID(),
                        orderId = orderId,
                        customerId = customerId,
                        paymentMethodId = paymentMethod.id,
                        type = PaymentType.EXTERNAL,
                        approvalState = PaymentApprovalState.APPROVED,
                        requestedAmountKrw = 1_000,
                        approvedAmountKrw = 1_000,
                        succeededRefundAmountKrw = 1_000,
                        currency = "KRW",
                        sourceReference = "payment:$orderId",
                        providerTransactionReference = "provider-payment:$orderId",
                        correlationId = "correlation:$orderId",
                        approvedAt = PAID_AT,
                        createdAt = CREATED_AT,
                        updatedAt = PAID_AT,
                    ),
                )
            val refundId = UUID.randomUUID()
            refunds.saveAndFlush(
                RefundEntity(
                    id = refundId,
                    paymentId = payment.id,
                    orderId = orderId,
                    requestedAmountKrw = 1_000,
                    succeededAmountKrw = 1_000,
                    reason = refundReason,
                    customerReasonCode = if (refundReason == "CUSTOMER_ORDER_CANCELLED") "OTHER" else null,
                    state = RefundState.SUCCEEDED,
                    providerRefundReference = "provider-refund:$refundId",
                    providerIdempotencyKey = "refund-key:$refundId",
                    sourceReference = storedRefundSource ?: source,
                    correlationId = if (refundReason == "CUSTOMER_ORDER_CANCELLED") "correlation:$orderId" else null,
                    attemptCount = 1,
                    requestAttemptCount = 1,
                    lookupAttemptCount = 0,
                    nextAction = RefundClaimMode.REQUEST,
                    nextAttemptAt = null,
                    createdAt = CANCELLED_AT,
                    updatedAt = REFUND_SUCCEEDED_AT,
                ),
            )
            jdbcTemplate.update(
                """
                INSERT INTO payment_cancellation_recovery_snapshot (
                    id, payment_id, order_id, cancellation_order_version,
                    approved_amount_krw, succeeded_refund_amount_before_cancellation_krw,
                    cancellation_requested_refund_amount_krw, cancellation_refund_id,
                    refund_source_reference, provider_idempotency_key, correlation_id,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 1000, 0, 1000, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                payment.id,
                orderId,
                orderVersion,
                refundId,
                source,
                "refund-key:$refundId",
                "correlation:$orderId",
                Timestamp.from(CANCELLED_AT),
                Timestamp.from(CANCELLED_AT),
            )
            val refundVersion = value<Long>("SELECT version FROM payment_refund WHERE id = ?", refundId)
            val event =
                PaymentRefundedV1(
                    envelope =
                        EventEnvelope(
                            eventId = UUID.randomUUID(),
                            eventType = "PaymentRefundedV1",
                            aggregateId = refundId,
                            aggregateVersion = refundVersion,
                            occurredAt = REFUND_SUCCEEDED_AT,
                            payloadVersion = 1,
                            correlationId = "correlation:$orderId",
                            causationId = "refund:$refundId:succeeded",
                        ),
                    refundId = refundId,
                    refundSource = source,
                    orderId = orderId,
                    customerId = customerId,
                    refundSucceededAt = REFUND_SUCCEEDED_AT,
                    currency = "KRW",
                    cashRefundedKrw = 1_000,
                    completionDisposition = RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION,
                )
            return Fixture(orderId, storeId, refundId, source, event)
        }

        private fun insertUnexpectedItem(fixture: Fixture) {
            val batchId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version)
                VALUES (?, ?, ?, 'OPEN', ?, 0)
                """.trimIndent(),
                batchId,
                fixture.storeId,
                SETTLEMENT_DATE,
                Timestamp.from(REFUND_SUCCEEDED_AT),
            )
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'KRW',
                          1000, 500, 50, 0, 0, 0, 950, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                batchId,
                fixture.orderId,
                fixture.storeId,
                "unexpected:${fixture.orderId}",
                Timestamp.from(REFUND_SUCCEEDED_AT),
                SETTLEMENT_DATE,
                Timestamp.from(REFUND_SUCCEEDED_AT),
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

        private fun exclude(event: PaymentRefundedV1) {
            transactions.executeWithoutResult { service.exclude(event, PROCESSED_AT) }
        }

        private fun assertConflict(
            event: PaymentRefundedV1,
            message: String,
        ) {
            assertThatThrownBy { exclude(event) }
                .isInstanceOf(DomainFailure::class.java)
                .hasMessageContaining("SETTLEMENT_SOURCE_CONFLICT")
                .hasMessageContaining(message)
        }

        private fun exclusionAuditCount(): Long =
            count(
                "SELECT count(*) FROM operations_audit_record " +
                    "WHERE action = 'SETTLEMENT_REFUND_EXCLUDED'",
            )

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

        private fun count(
            sql: String,
            vararg arguments: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *arguments))

        private inline fun <reified T : Any> value(
            sql: String,
            vararg arguments: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *arguments))

        private data class Fixture(
            val orderId: UUID,
            val storeId: UUID,
            val refundId: UUID,
            val source: String,
            val event: PaymentRefundedV1,
        )

        private companion object {
            val CREATED_AT: Instant = Instant.parse("2026-08-03T00:00:00Z")
            val PAID_AT: Instant = Instant.parse("2026-08-03T00:01:00Z")
            val CANCELLED_AT: Instant = Instant.parse("2026-08-03T00:02:00Z")
            val REFUND_SUCCEEDED_AT: Instant = Instant.parse("2026-08-03T00:03:00Z")
            val PROCESSED_AT: Instant = Instant.parse("2026-08-03T00:04:00Z")
            val SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 8, 3)
        }
    }
