package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ApplyExternalPaymentResultCommand
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class PaymentCommitmentRecoveryTransaction(
    private val orders: OrderJpaRepository,
    private val payments: ExternalPaymentOperations,
    private val responses: PaymentConfirmationResponseFactory,
    private val orderReferences: PaymentOrderReferenceProjection,
    private val audits: AuditRecordOperations,
) {
    /**
     * Runs only after the approval-commit transaction has rolled back. The
     * Order row is re-read under lock so a concurrent winner that already
     * committed PAID is never scheduled for a compensating void/refund.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverApproved(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        result: ProviderPaymentResult.Approved,
        failureCode: String,
        now: Instant,
    ): StoredHttpResponse {
        val order =
            orders.findLockedById(orderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        if (order.checkoutMode != CheckoutMode.IMMEDIATE) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Payment commitment recovery only applies to immediate checkout")
        }
        val current = payments.current(paymentId)
        if (order.state == OrderState.PAID) {
            if (current.approvalState != "APPROVED") {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Paid order and payment approval state do not agree",
                )
            }
            return responses.current(current, orderReferences.resolveOwned(customerId, orderId), replay = true)
        }
        if (order.state == OrderState.CANCELLED &&
            order.cancellationCause == OrderCancellationCause.PAYMENT_COMMITMENT_FAILED
        ) {
            return responses.current(current, orderReferences.resolveOwned(customerId, orderId), replay = true)
        }
        if (current.approvalState == "APPROVED") {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Approved payment cannot be compensated before the committed order winner is resolved",
            )
        }
        if (order.state == OrderState.PENDING_PAYMENT) {
            order.cancelAfterPaymentCommitmentFailed(now, failureCode)
        } else if (order.state !in setOf(OrderState.CANCELLED, OrderState.EXPIRED, OrderState.REJECTED)) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order state does not allow payment commitment recovery")
        }

        val body =
            responses.confirmationBody(
                paymentId = paymentId,
                orderReference = orderReferences.resolve(orderId),
                approvalState = "RECONCILING",
                approvedAmountKrw = result.amountKrw,
                currency = result.currency,
                recoveryState = "REQUESTED",
                now = now,
                correlationId = current.correlationId,
            )
        payments.applyResult(
            ApplyExternalPaymentResultCommand(
                paymentId = paymentId,
                result = result,
                responseStatus = 202,
                responseBody = body,
                now = now,
                lateApproval = true,
            ),
        )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = customerId.toString(),
                    actorType = AuditActorType.CUSTOMER,
                    category = AuditCategory.FINANCIAL_TRANSACTION,
                    action = "PAYMENT_COMMITMENT_RECOVERY_REQUESTED",
                    targetType = "PAYMENT",
                    targetId = paymentId,
                    occurredAt = now,
                    reason = failureCode,
                    beforeSummary = mapOf("orderState" to "PENDING_PAYMENT"),
                    afterSummary = mapOf("orderState" to order.state.name, "recoveryState" to "REQUESTED"),
                    correlationId = current.correlationId,
                    sourceReference = "payment:$paymentId:commitment-recovery",
                ),
            ),
        )
        return StoredHttpResponse(202, body)
    }
}
