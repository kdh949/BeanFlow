package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentResult
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class OrderCreationResponseFactory(
    private val objectMapper: ObjectMapper,
) {
    fun create(
        order: Order,
        payment: BenefitOnlyPaymentResult?,
    ): StoredHttpResponse =
        StoredHttpResponse(
            status = 201,
            body =
                objectMapper.writeValueAsString(
                    if (payment == null) pending(order) else benefitOnly(order, payment),
                ),
        )

    internal fun pending(order: Order) =
        PendingPaymentOrderCreationResponse(
            order =
                OrderResponse(
                    orderId = order.id,
                    storeId = order.storeId,
                    publicReference = order.displayIdentity.publicReference,
                    pickupNumber = order.displayIdentity.pickupNumber,
                    pickupBusinessDate = order.displayIdentity.pickupBusinessDate,
                    storeName = order.displayIdentity.storeName,
                    pickupWindowStart = order.displayIdentity.pickupWindowStart,
                    pickupWindowEnd = order.displayIdentity.pickupWindowEnd,
                    state = order.state.name,
                    reservationExpiresAt = order.reservationExpiresAt,
                    paymentRecovery = null,
                    paidAt = order.paidAt,
                    acceptanceWarningAt = order.acceptanceWarningAt,
                    acceptanceWarningRequestedAt = null,
                    acceptanceDeadlineAt = order.acceptanceDeadlineAt,
                    acceptedAt = null,
                    rejectedAt = null,
                    preparingAt = null,
                    readyAt = null,
                    completedAt = null,
                    cancelledAt = null,
                    cancellationCause = null,
                    cancellationReasonCode = null,
                    rejectionReason = null,
                    lines = lines(order),
                    subtotalKrw = order.subtotalKrw,
                    couponDiscountKrw = order.couponDiscountKrw,
                    pointsAppliedKrw = order.pointsAppliedKrw,
                    payableKrw = order.payableKrw,
                    currency = "KRW",
                    createdAt = order.createdAt,
                    updatedAt = order.createdAt,
                ),
        )

    internal fun benefitOnly(
        order: Order,
        payment: BenefitOnlyPaymentResult,
    ) = BenefitOnlyOrderCreationResponse(
        order =
            BenefitOnlyOrderResponse(
                orderId = order.id,
                storeId = order.storeId,
                publicReference = order.displayIdentity.publicReference,
                pickupNumber = order.displayIdentity.pickupNumber,
                pickupBusinessDate = order.displayIdentity.pickupBusinessDate,
                storeName = order.displayIdentity.storeName,
                pickupWindowStart = order.displayIdentity.pickupWindowStart,
                pickupWindowEnd = order.displayIdentity.pickupWindowEnd,
                state = order.state.name,
                lines = lines(order),
                subtotalKrw = order.subtotalKrw,
                couponDiscountKrw = order.couponDiscountKrw,
                pointsAppliedKrw = order.pointsAppliedKrw,
                payableKrw = order.payableKrw,
                currency = "KRW",
                createdAt = order.createdAt,
                updatedAt = order.createdAt,
            ),
        payment =
            BenefitOnlyPaymentResponse(
                paymentId = payment.paymentId,
                orderId = payment.orderId,
                type = payment.type,
                approvalState = payment.approvalState,
                approvedAmountKrw = payment.approvedAmountKrw,
                currency = payment.currency,
                updatedAt = payment.updatedAt,
                correlationId = payment.correlationId,
            ),
    )

    private fun lines(order: Order): List<OrderLineResponse> =
        order.lines.map { line ->
            OrderLineResponse(
                orderLineId = line.id,
                menuId = line.menuId,
                menuName = line.menuName,
                optionNames = line.options.map { it.name },
                unitPriceKrw = line.unitPriceKrw,
                quantity = line.quantity,
                couponDiscountKrw = line.couponDiscountKrw,
                pointsAppliedKrw = line.pointsAppliedKrw,
                cashPaidKrw = line.cashPayableKrw,
            )
        }
}
