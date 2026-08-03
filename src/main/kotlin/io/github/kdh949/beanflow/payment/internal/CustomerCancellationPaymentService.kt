package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentSnapshot
import io.github.kdh949.beanflow.payment.api.PrepareCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerCancellationPaymentService(
    private val payments: PaymentJpaRepository,
    private val refunds: RefundJpaRepository,
    private val snapshots: CustomerCancellationPaymentSnapshotJpaRepository,
    private val identifiers: IdentifierSource,
    private val jdbcTemplate: JdbcTemplate,
) : CustomerCancellationPaymentOperations {
    @Transactional(readOnly = true)
    override fun findSnapshot(orderId: UUID): CustomerCancellationPaymentSnapshot? =
        snapshots.findByOrderId(orderId)?.let { snapshot ->
            val payment =
                payments.findById(snapshot.paymentId).orElse(null)
                    ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Cancellation snapshot payment is missing")
            snapshot.toApi(payment.type)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun prepare(command: PrepareCustomerCancellationPaymentCommand): CustomerCancellationPaymentSnapshot {
        snapshots.findByOrderId(command.orderId)?.let { return replay(it, command) }
        val payment =
            payments.findLockedByOrderId(command.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Paid order payment is missing")
        val lockedRefunds = refunds.findAllLockedByPaymentId(payment.id)
        lockSuccessfulAllocations(payment.id)
        snapshots.findByOrderId(command.orderId)?.let { return replay(it, command) }

        if (payment.approvalState != PaymentApprovalState.APPROVED) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Paid order payment is not approved")
        }
        if (lockedRefunds.any { it.state in UNRESOLVED_REFUND_STATES }) {
            fail(FailureCode.PAYMENT_REFUND_UNRESOLVED, "Payment has an unresolved Refund")
        }

        val approvedAmount =
            payment.approvedAmountKrw
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved payment amount is missing")
        if (payment.succeededRefundAmountKrw !in 0..approvedAmount) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment refund total is inconsistent")
        }
        val requestedAmount = Math.subtractExact(approvedAmount, payment.succeededRefundAmountKrw)
        if (payment.type == PaymentType.BENEFIT_ONLY && requestedAmount != 0L) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Benefit-only payment has a refundable cash amount")
        }

        val refundId = if (requestedAmount > 0) identifiers.next() else null
        val source = sourceReference(command).takeIf { requestedAmount > 0 }
        val providerKey = providerIdempotencyKey(command).takeIf { requestedAmount > 0 }
        if (refundId != null) {
            refunds.save(
                RefundEntity(
                    id = refundId,
                    paymentId = payment.id,
                    orderId = command.orderId,
                    requestedAmountKrw = requestedAmount,
                    succeededAmountKrw = null,
                    reason = REASON,
                    customerReasonCode = command.customerReasonCode,
                    state = RefundState.REQUESTED,
                    providerRefundReference = null,
                    providerIdempotencyKey = requireNotNull(providerKey),
                    sourceReference = requireNotNull(source),
                    correlationId = command.correlationId,
                    attemptCount = 0,
                    requestAttemptCount = 0,
                    lookupAttemptCount = 0,
                    nextAction = RefundClaimMode.REQUEST,
                    nextAttemptAt = command.now,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
        }
        val snapshot =
            snapshots.save(
                CustomerCancellationPaymentSnapshotEntity(
                    id = identifiers.next(),
                    paymentId = payment.id,
                    orderId = command.orderId,
                    cancellationOrderVersion = command.cancellationOrderVersion,
                    approvedAmountKrw = approvedAmount,
                    succeededRefundAmountBeforeCancellationKrw = payment.succeededRefundAmountKrw,
                    cancellationRequestedRefundAmountKrw = requestedAmount,
                    cancellationRefundId = refundId,
                    refundSourceReference = source,
                    providerIdempotencyKey = providerKey,
                    correlationId = command.correlationId,
                    createdAt = command.now,
                    updatedAt = command.now,
                ),
            )
        return snapshot.toApi(payment.type)
    }

    private fun replay(
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        command: PrepareCustomerCancellationPaymentCommand,
    ): CustomerCancellationPaymentSnapshot {
        if (snapshot.cancellationOrderVersion != command.cancellationOrderVersion ||
            snapshot.correlationId != command.correlationId
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Customer cancellation payment snapshot conflicts with the command")
        }
        val payment =
            payments.findById(snapshot.paymentId).orElse(null)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Cancellation snapshot payment is missing")
        return snapshot.toApi(payment.type)
    }

    private fun lockSuccessfulAllocations(paymentId: UUID) {
        jdbcTemplate.query(
            """
            SELECT allocation.id
              FROM payment_refund_line_allocation allocation
              JOIN payment_refund refund ON refund.id = allocation.refund_id
             WHERE refund.payment_id = ?
             ORDER BY allocation.order_line_id, allocation.id
             FOR UPDATE OF allocation
            """.trimIndent(),
            { _, _ -> Unit },
            paymentId,
        )
        jdbcTemplate.query(
            """
            SELECT allocation.id
              FROM payment_refund_point_allocation allocation
              JOIN payment_refund refund ON refund.id = allocation.refund_id
             WHERE refund.payment_id = ?
             ORDER BY allocation.point_reservation_allocation_id, allocation.id
             FOR UPDATE OF allocation
            """.trimIndent(),
            { _, _ -> Unit },
            paymentId,
        )
    }

    private fun sourceReference(command: PrepareCustomerCancellationPaymentCommand): String =
        "order:${command.orderId}:customer-cancellation:${command.cancellationOrderVersion}:payment"

    private fun providerIdempotencyKey(command: PrepareCustomerCancellationPaymentCommand): String =
        "refund:customer-cancellation:${command.orderId}:${command.cancellationOrderVersion}"

    private fun CustomerCancellationPaymentSnapshotEntity.toApi(type: PaymentType) =
        CustomerCancellationPaymentSnapshot(
            snapshotId = id,
            paymentId = paymentId,
            paymentType = type.name,
            approvedAmountKrw = approvedAmountKrw,
            succeededRefundAmountBeforeCancellationKrw = succeededRefundAmountBeforeCancellationKrw,
            requestedRefundAmountKrw = cancellationRequestedRefundAmountKrw,
            refundId = cancellationRefundId,
            refundSourceReference = refundSourceReference,
            providerIdempotencyKey = providerIdempotencyKey,
            updatedAt = updatedAt,
        )

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val REASON = "CUSTOMER_ORDER_CANCELLED"
        val UNRESOLVED_REFUND_STATES =
            setOf(
                RefundState.REQUESTED,
                RefundState.PROCESSING,
                RefundState.RETRY_SCHEDULED,
                RefundState.UNKNOWN,
                RefundState.RECONCILING,
                RefundState.MANUAL_REVIEW,
            )
    }
}
