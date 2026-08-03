package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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
@SpringBootTest(
    properties = [
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
internal class CustomerCancellationPaymentServiceTest
    @Autowired
    constructor(
        private val operations: CustomerCancellationPaymentOperations,
        private val payments: PaymentJpaRepository,
        private val paymentMethods: PaymentMethodJpaRepository,
        private val refunds: RefundJpaRepository,
        private val snapshots: CustomerCancellationPaymentSnapshotJpaRepository,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    payment_cancellation_recovery_snapshot,
                    payment_refund_point_allocation,
                    payment_refund_line_allocation,
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
        fun `approved external payment creates one exact remaining Refund and durable snapshot`() {
            val orderId = UUID.randomUUID()
            val payment = externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 3_000)

            val first = prepare(orderId, cancellationVersion = 8)
            val replay = prepare(orderId, cancellationVersion = 8)

            assertThat(first).isEqualTo(replay)
            assertThat(first.paymentId).isEqualTo(payment.id)
            assertThat(first.requestedRefundAmountKrw).isEqualTo(7_000)
            assertThat(first.paymentRecoveryRequired).isTrue()
            assertThat(refunds.count()).isEqualTo(1)
            assertThat(snapshots.count()).isEqualTo(1)
            val refund = refunds.findAll().single()
            assertThat(refund.requestedAmountKrw).isEqualTo(7_000)
            assertThat(refund.reason).isEqualTo("CUSTOMER_ORDER_CANCELLED")
            assertThat(refund.customerReasonCode).isEqualTo("CHANGED_MIND")
            assertThat(refund.state).isEqualTo(RefundState.REQUESTED)
            assertThat(refund.nextAttemptAt).isEqualTo(NOW)
            assertThat(refund.providerIdempotencyKey).isEqualTo("refund:customer-cancellation:$orderId:8")
            assertThat(refund.sourceReference).isEqualTo("order:$orderId:customer-cancellation:8:payment")
        }

        @Test
        fun `benefit-only payment records zero snapshot without a Refund`() {
            val orderId = UUID.randomUUID()
            benefitOnlyPayment(orderId)

            val result = prepare(orderId, cancellationVersion = 3)

            assertThat(result.paymentType).isEqualTo("BENEFIT_ONLY")
            assertThat(result.requestedRefundAmountKrw).isZero()
            assertThat(result.refundId).isNull()
            assertThat(result.paymentRecoveryRequired).isFalse()
            assertThat(refunds.count()).isZero()
            assertThat(snapshots.count()).isEqualTo(1)
        }

        @Test
        fun `unresolved prior Refund rejects cancellation with its explicit failure code`() {
            val orderId = UUID.randomUUID()
            val payment = externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 0)
            refunds.save(
                RefundEntity(
                    id = UUID.randomUUID(),
                    paymentId = payment.id,
                    orderId = orderId,
                    requestedAmountKrw = 1_000,
                    reason = "STORE_ORDER_REJECTED",
                    state = RefundState.UNKNOWN,
                    providerIdempotencyKey = "refund:prior:${payment.id}",
                    sourceReference = "prior:${payment.id}",
                    attemptCount = 1,
                    requestAttemptCount = 1,
                    lookupAttemptCount = 0,
                    nextAction = RefundClaimMode.LOOKUP,
                    nextAttemptAt = NOW,
                    lastFailureCode = "ACK_LOST",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            assertThatThrownBy { prepare(orderId, cancellationVersion = 9) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.PAYMENT_REFUND_UNRESOLVED)
                }
            assertThat(snapshots.count()).isZero()
            assertThat(refunds.count()).isEqualTo(1)
        }

        private fun prepare(
            orderId: UUID,
            cancellationVersion: Long,
        ) = requireNotNull(
            transactions.execute {
                operations.prepare(
                    PrepareCustomerCancellationPaymentCommand(
                        orderId = orderId,
                        cancellationOrderVersion = cancellationVersion,
                        customerReasonCode = "CHANGED_MIND",
                        correlationId = "customer-cancellation-test",
                        now = NOW,
                    ),
                )
            },
        )

        private fun externalPayment(
            orderId: UUID,
            approvedAmount: Long,
            succeededRefundAmount: Long,
        ): PaymentEntity {
            insertPaidOrder(orderId)
            val customerId = UUID.randomUUID()
            val method =
                paymentMethods.save(
                    PaymentMethodEntity(
                        id = UUID.randomUUID(),
                        customerId = customerId,
                        provider = "TEST_PROVIDER",
                        tokenReference = "token:$orderId",
                        displayAlias = "test card",
                        cardBrand = "VISA",
                        lastFour = "4242",
                        status = PaymentMethodStatus.ACTIVE,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                )
            return payments.save(
                PaymentEntity(
                    id = UUID.randomUUID(),
                    orderId = orderId,
                    customerId = customerId,
                    paymentMethodId = method.id,
                    type = PaymentType.EXTERNAL,
                    approvalState = PaymentApprovalState.APPROVED,
                    requestedAmountKrw = approvedAmount,
                    approvedAmountKrw = approvedAmount,
                    succeededRefundAmountKrw = succeededRefundAmount,
                    currency = "KRW",
                    sourceReference = "payment:$orderId",
                    providerTransactionReference = "provider:$orderId",
                    correlationId = "payment-test",
                    approvedAt = NOW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        }

        private fun benefitOnlyPayment(orderId: UUID): PaymentEntity =
            insertPaidOrder(orderId).let {
                payments.save(
                    PaymentEntity(
                        id = UUID.randomUUID(),
                        orderId = orderId,
                        type = PaymentType.BENEFIT_ONLY,
                        approvalState = PaymentApprovalState.APPROVED,
                        requestedAmountKrw = 0,
                        approvedAmountKrw = 0,
                        succeededRefundAmountKrw = 0,
                        currency = "KRW",
                        benefitSnapshotReference = "benefit:$orderId",
                        sourceReference = "payment:$orderId",
                        correlationId = "payment-test",
                        approvedAt = NOW,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                )
            }

        private fun insertPaidOrder(orderId: UUID) {
            val storeId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id, state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, 10000, 'KRW', NULL,
                              ?, ?, ?, ?, ?, 7)
                    """.trimIndent(),
                    orderId,
                    UUID.randomUUID(),
                    storeId,
                    UUID.randomUUID(),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(120)),
                    Timestamp.from(NOW.plusSeconds(180)),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW),
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-03T01:00:00Z")
        }
    }
