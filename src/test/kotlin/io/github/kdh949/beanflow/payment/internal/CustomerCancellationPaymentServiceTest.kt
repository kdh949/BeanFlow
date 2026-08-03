package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.api.ProjectCustomerCancellationPaymentCommand
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                    operations_audit_record,
                    operations_reprocessing_case,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `customer projection maps requested and terminal failure without exposing internal state`() {
            val orderId = UUID.randomUUID()
            externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 3_000)
            prepare(orderId, cancellationVersion = 8)

            val requested = project(orderId, cancellationVersion = 8)
            assertThat(requested.state).isEqualTo("REQUESTED")
            assertThat(requested.noticeCode).isNull()
            assertThat(requested.approvedAmountKrw).isEqualTo(10_000)
            assertThat(requested.remainingRefundableAmountKrw).isEqualTo(7_000)

            val refund = refunds.findAll().single()
            refund.state = RefundState.FAILED
            refund.nextAttemptAt = null
            refund.lastFailureCode = "PROVIDER_DECLINED"
            refunds.saveAndFlush(refund)

            val delayed = project(orderId, cancellationVersion = 8)
            assertThat(delayed.state).isEqualTo("PROCESSING")
            assertThat(delayed.noticeCode).isEqualTo("REFUND_DELAYED")
            assertThat(delayed.toString()).doesNotContain("FAILED", "MANUAL_REVIEW", "PROVIDER_DECLINED")
        }

        @Test
        fun `missing snapshot returns delayed without inferred amounts and records one setup case and audit`() {
            val orderId = UUID.randomUUID()
            externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 0)

            val first = project(orderId, cancellationVersion = 8)
            val replay = project(orderId, cancellationVersion = 8)

            assertThat(first).isEqualTo(replay)
            assertThat(first.state).isEqualTo("PROCESSING")
            assertThat(first.noticeCode).isEqualTo("REFUND_DELAYED")
            assertThat(first.approvedAmountKrw).isNull()
            assertThat(first.succeededRefundAmountBeforeCancellationKrw).isNull()
            assertThat(first.cancellationRequestedRefundAmountKrw).isNull()
            assertThat(first.remainingRefundableAmountKrw).isNull()
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from operations_reprocessing_case " +
                        "where case_type = 'PAYMENT_CANCELLATION_SETUP'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from operations_audit_record " +
                        "where action = 'PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `benefit only projection is not required with four verified zero amounts`() {
            val orderId = UUID.randomUUID()
            benefitOnlyPayment(orderId)
            prepare(orderId, cancellationVersion = 8)

            val projection = project(orderId, cancellationVersion = 8)

            assertThat(projection.state).isEqualTo("NOT_REQUIRED")
            assertThat(projection.approvedAmountKrw).isZero()
            assertThat(projection.succeededRefundAmountBeforeCancellationKrw).isZero()
            assertThat(projection.cancellationRequestedRefundAmountKrw).isZero()
            assertThat(projection.remainingRefundableAmountKrw).isZero()
            assertThat(jdbcTemplate.queryForObject("select count(*) from operations_reprocessing_case", Long::class.java))
                .isZero()
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
        fun `succeeded and failed prior Refunds allow only the exact remaining amount`() {
            val orderId = UUID.randomUUID()
            val payment = externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 3_000)
            refunds.saveAllAndFlush(
                listOf(
                    RefundEntity(
                        id = UUID.randomUUID(),
                        paymentId = payment.id,
                        orderId = orderId,
                        requestedAmountKrw = 3_000,
                        succeededAmountKrw = 3_000,
                        reason = "HISTORICAL_SUCCESS",
                        state = RefundState.SUCCEEDED,
                        providerRefundReference = "provider:prior-success:${payment.id}",
                        providerIdempotencyKey = "refund:prior-success:${payment.id}",
                        sourceReference = "prior-success:${payment.id}",
                        attemptCount = 1,
                        requestAttemptCount = 1,
                        nextAction = RefundClaimMode.REQUEST,
                        nextAttemptAt = null,
                        providerRequestStartedAt = NOW,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                    RefundEntity(
                        id = UUID.randomUUID(),
                        paymentId = payment.id,
                        orderId = orderId,
                        requestedAmountKrw = 1_000,
                        reason = "HISTORICAL_FAILURE",
                        state = RefundState.FAILED,
                        providerIdempotencyKey = "refund:prior-failed:${payment.id}",
                        sourceReference = "prior-failed:${payment.id}",
                        attemptCount = 1,
                        requestAttemptCount = 1,
                        nextAction = RefundClaimMode.REQUEST,
                        nextAttemptAt = null,
                        providerRequestStartedAt = NOW,
                        lastFailureCode = "DECLINED",
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                ),
            )

            val result = prepare(orderId, cancellationVersion = 10)

            assertThat(result.succeededRefundAmountBeforeCancellationKrw).isEqualTo(3_000)
            assertThat(result.requestedRefundAmountKrw).isEqualTo(7_000)
            assertThat(refunds.findBySourceReference("order:$orderId:customer-cancellation:10:payment")?.requestedAmountKrw)
                .isEqualTo(7_000)
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
        fun `every unresolved prior Refund state rejects cancellation with its explicit failure code`() {
            val unresolved =
                listOf(
                    RefundState.REQUESTED,
                    RefundState.PROCESSING,
                    RefundState.RETRY_SCHEDULED,
                    RefundState.UNKNOWN,
                    RefundState.RECONCILING,
                    RefundState.MANUAL_REVIEW,
                )

            unresolved.forEachIndexed { index, state ->
                val orderId = UUID.randomUUID()
                val payment = externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 0)
                refunds.saveAndFlush(unresolvedRefund(payment, orderId, state))

                assertThatThrownBy { prepare(orderId, cancellationVersion = 9L + index) }
                    .isInstanceOfSatisfying(DomainFailure::class.java) {
                        assertThat(it.code).isEqualTo(FailureCode.PAYMENT_REFUND_UNRESOLVED)
                    }
            }

            assertThat(snapshots.count()).isZero()
            assertThat(refunds.count()).isEqualTo(unresolved.size.toLong())
        }

        @Test
        fun `payment lock serializes a competing Refund before cancellation snapshot`() {
            val orderId = UUID.randomUUID()
            val payment = externalPayment(orderId, approvedAmount = 10_000, succeededRefundAmount = 0)
            val locked = CountDownLatch(1)
            val allowCommit = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val competingRefund =
                executor.submit {
                    transactions.executeWithoutResult {
                        requireNotNull(payments.findLockedById(payment.id))
                        refunds.save(
                            RefundEntity(
                                id = UUID.randomUUID(),
                                paymentId = payment.id,
                                orderId = orderId,
                                requestedAmountKrw = 1_000,
                                reason = "COMPETING_REFUND",
                                state = RefundState.REQUESTED,
                                providerIdempotencyKey = "refund:competing:${payment.id}",
                                sourceReference = "competing:${payment.id}",
                                attemptCount = 0,
                                nextAttemptAt = NOW,
                                createdAt = NOW,
                                updatedAt = NOW,
                            ),
                        )
                        locked.countDown()
                        assertThat(allowCommit.await(10, TimeUnit.SECONDS)).isTrue()
                    }
                }
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            val cancellation =
                executor.submit<Throwable?> {
                    try {
                        prepare(orderId, cancellationVersion = 11)
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
            allowCommit.countDown()
            competingRefund.get(10, TimeUnit.SECONDS)
            val failure = cancellation.get(10, TimeUnit.SECONDS)
            executor.shutdown()

            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PAYMENT_REFUND_UNRESOLVED)
            }
            assertThat(snapshots.count()).isZero()
            assertThat(refunds.count()).isEqualTo(1)
        }

        private fun unresolvedRefund(
            payment: PaymentEntity,
            orderId: UUID,
            state: RefundState,
        ): RefundEntity {
            val requestAttempts = if (state == RefundState.REQUESTED) 0 else 1
            val lookupAttempts = if (state == RefundState.RECONCILING) 1 else 0
            val claimed = state == RefundState.PROCESSING || state == RefundState.RECONCILING
            val scheduled = state == RefundState.REQUESTED || state == RefundState.RETRY_SCHEDULED || state == RefundState.UNKNOWN
            return RefundEntity(
                id = UUID.randomUUID(),
                paymentId = payment.id,
                orderId = orderId,
                requestedAmountKrw = 1_000,
                reason = "STORE_ORDER_REJECTED",
                state = state,
                providerIdempotencyKey = "refund:prior:${payment.id}",
                sourceReference = "prior:${payment.id}",
                attemptCount = requestAttempts + lookupAttempts,
                requestAttemptCount = requestAttempts,
                lookupAttemptCount = lookupAttempts,
                nextAction =
                    if (state == RefundState.UNKNOWN || state == RefundState.RECONCILING) {
                        RefundClaimMode.LOOKUP
                    } else {
                        RefundClaimMode.REQUEST
                    },
                nextAttemptAt = NOW.takeIf { scheduled },
                providerRequestStartedAt = NOW.takeIf { requestAttempts > 0 },
                claimToken = UUID.randomUUID().takeIf { claimed },
                claimUntil = NOW.plusSeconds(60).takeIf { claimed },
                lastFailureCode = "PRIOR_REFUND_UNRESOLVED",
                createdAt = NOW,
                updatedAt = NOW,
            )
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

        private fun project(
            orderId: UUID,
            cancellationVersion: Long,
        ) = operations.project(
            ProjectCustomerCancellationPaymentCommand(
                orderId = orderId,
                cancellationOrderVersion = cancellationVersion,
                paymentExpected = true,
                correlationId = "customer-cancellation-$orderId",
                now = NOW,
            ),
        )

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
