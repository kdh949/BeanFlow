package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.OrderRejectionCause
import io.github.kdh949.beanflow.ordering.api.OrderRejectionSourceActorType
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
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed settlement exclusion evidence")
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
internal class StoreRejectionRefundExclusionIntegrationTest
    @Autowired
    constructor(
        private val service: StoreRejectionRefundExclusionService,
        private val paymentMethods: PaymentMethodJpaRepository,
        private val payments: PaymentJpaRepository,
        private val refunds: RefundJpaRepository,
        private val auditRecords: AuditRecordOperations,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication,
                    payment_refund,
                    payment_payment,
                    payment_method,
                    ordering_order
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `store rejection appends one cause specific audit across replay without ledger rows`() {
            val fixture = fixture(OrderRejectionCause.STORE_REJECTION, OrderRejectionSourceActorType.STORE_STAFF)

            exclude(fixture.event)
            exclude(fixture.event.copy(envelope = fixture.event.envelope.copy(eventId = UUID.randomUUID())))

            assertThat(auditCount(fixture, "ORDER_NOT_COMPLETED_STORE_REJECTION")).isOne()
            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", fixture.orderId)).isZero()
            assertThat(count("SELECT count(*) FROM settlement_adjustment WHERE adjustment_source = ?", fixture.source)).isZero()
        }

        @Test
        fun `acceptance timeout uses closed cause instead of free form rejection reason`() {
            val fixture =
                fixture(
                    OrderRejectionCause.ACCEPTANCE_TIMEOUT,
                    OrderRejectionSourceActorType.SYSTEM_TIMEOUT,
                    rejectionReason = "free form text must not classify the cause",
                )

            exclude(fixture.event)

            assertThat(auditCount(fixture, "ORDER_NOT_COMPLETED_ACCEPTANCE_TIMEOUT")).isOne()
            assertThat(auditCount(fixture, "ORDER_NOT_COMPLETED_STORE_REJECTION")).isZero()
        }

        @Test
        fun `missing legacy cause and changed source remain explicit conflicts`() {
            val missing = fixture(OrderRejectionCause.STORE_REJECTION, OrderRejectionSourceActorType.STORE_OWNER)
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER ordering_order_rejection_evidence_immutable")
            try {
                jdbcTemplate.update(
                    "UPDATE ordering_order SET rejection_cause = NULL, rejection_actor_type = NULL, " +
                        "rejection_event_id = NULL, rejection_terminal_version = NULL WHERE id = ?",
                    missing.orderId,
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER ordering_order_rejection_evidence_immutable")
            }
            assertConflict(missing.event, "terminal version is missing")

            cleanData()
            val changed = fixture(OrderRejectionCause.STORE_REJECTION, OrderRejectionSourceActorType.STORE_OWNER)
            assertConflict(changed.event.copy(refundSource = "event:${UUID.randomUUID()}:payment-refund"), "OrderRejectedV1")
            assertThat(exclusionAuditCount()).isZero()
        }

        @Test
        fun `audit insert failure rolls back and a later exact replay succeeds`() {
            val fixture = fixture(OrderRejectionCause.STORE_REJECTION, OrderRejectionSourceActorType.STORE_OWNER)
            jdbcTemplate.execute(
                "ALTER TABLE operations_audit_record ADD CONSTRAINT test_reject_store_refund_exclusion " +
                    "CHECK (action <> 'SETTLEMENT_REFUND_EXCLUDED')",
            )
            try {
                assertThatThrownBy { exclude(fixture.event) }.isInstanceOf(RuntimeException::class.java)
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE operations_audit_record DROP CONSTRAINT test_reject_store_refund_exclusion",
                )
            }
            assertThat(exclusionAuditCount()).isZero()

            exclude(fixture.event)
            assertThat(auditCount(fixture, "ORDER_NOT_COMPLETED_STORE_REJECTION")).isOne()
        }

        @Test
        fun `an existing audit with another cause is a source conflict`() {
            val fixture = fixture(OrderRejectionCause.STORE_REJECTION, OrderRejectionSourceActorType.STORE_OWNER)
            transactions.executeWithoutResult {
                auditRecords.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            actorId = "test",
                            actorType = AuditActorType.SYSTEM,
                            category = AuditCategory.SETTLEMENT_AND_DISPUTE,
                            action = "SETTLEMENT_REFUND_EXCLUDED",
                            targetType = "REFUND",
                            targetId = fixture.refundId,
                            occurredAt = PROCESSED_AT,
                            reason = "ORDER_NOT_COMPLETED_ACCEPTANCE_TIMEOUT",
                            correlationId = fixture.event.envelope.correlationId,
                            sourceReference = fixture.source,
                        ),
                    ),
                )
            }

            assertConflict(fixture.event, "Audit reason conflicts")
            assertThat(exclusionAuditCount()).isOne()
        }

        private fun fixture(
            cause: OrderRejectionCause,
            actorType: OrderRejectionSourceActorType,
            rejectionReason: String = "store supplied reason",
        ): Fixture {
            val customerId = UUID.randomUUID()
            val storeId = insertStore()
            val orderId = UUID.randomUUID()
            val eventId = UUID.randomUUID()
            val orderVersion = 7L
            val source = "event:$eventId:payment-refund"
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state, subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, rejected_at, rejection_reason,
                        rejection_cause, rejection_actor_type, rejection_event_id, rejection_terminal_version,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-09-14', ?,
                              'Test Store', '2026-09-14T00:00:00Z', '2026-09-14T00:10:00Z',
                              'REJECTED', 1000, 0, 0, 1000, 'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    Timestamp.from(REJECTED_AT),
                    rejectionReason,
                    cause.name,
                    actorType.name,
                    eventId,
                    orderVersion,
                    Timestamp.from(CREATED_AT),
                    Timestamp.from(REJECTED_AT),
                    orderVersion,
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }

            val method =
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
                        paymentMethodId = method.id,
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
                    reason = "STORE_ORDER_REJECTED",
                    state = RefundState.SUCCEEDED,
                    providerRefundReference = "provider-refund:$refundId",
                    providerIdempotencyKey = "refund-key:$refundId",
                    sourceReference = source,
                    attemptCount = 1,
                    requestAttemptCount = 1,
                    lookupAttemptCount = 0,
                    nextAction = RefundClaimMode.REQUEST,
                    nextAttemptAt = null,
                    createdAt = REJECTED_AT,
                    updatedAt = REFUND_SUCCEEDED_AT,
                ),
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
            return Fixture(orderId, refundId, source, event)
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

        private fun auditCount(
            fixture: Fixture,
            reason: String,
        ): Long =
            count(
                "SELECT count(*) FROM operations_audit_record " +
                    "WHERE action = 'SETTLEMENT_REFUND_EXCLUDED' AND target_id = ? " +
                    "AND source_reference = ? AND reason = ?",
                fixture.refundId,
                fixture.source,
                reason,
            )

        private fun exclusionAuditCount(): Long =
            count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_REFUND_EXCLUDED'")

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
            val refundId: UUID,
            val source: String,
            val event: PaymentRefundedV1,
        )

        private companion object {
            val CREATED_AT: Instant = Instant.parse("2026-09-14T00:00:00Z")
            val PAID_AT: Instant = Instant.parse("2026-09-14T00:01:00Z")
            val REJECTED_AT: Instant = Instant.parse("2026-09-14T00:02:00Z")
            val REFUND_SUCCEEDED_AT: Instant = Instant.parse("2026-09-14T00:03:00Z")
            val PROCESSED_AT: Instant = Instant.parse("2026-09-14T00:04:00Z")
        }
    }
