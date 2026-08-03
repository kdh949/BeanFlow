package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

@Service
internal class GetOrderService(
    private val expiryUseCase: ReservationExpiryUseCase,
    private val orderRepository: OrderJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val cancellationPayments: CustomerCancellationPaymentOperations,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun get(
        customerId: UUID,
        orderId: UUID,
    ): OrderResponse {
        assertOwnership(customerId, orderId)
        expiryUseCase.expireIfDue(orderId, clock.instant())
        return load(customerId, orderId)
    }

    @Transactional(readOnly = true)
    fun assertOwnership(
        customerId: UUID,
        orderId: UUID,
    ) {
        val order =
            orderRepository.findById(orderId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
    }

    @Transactional(readOnly = true)
    fun load(
        customerId: UUID,
        orderId: UUID,
    ): OrderResponse {
        val order =
            orderRepository.findById(orderId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        val lines = orderLineRepository.findAllByOrderIdOrderByLineSequence(orderId)
        val paymentRecovery =
            if (order.cancellationCause == OrderCancellationCause.CUSTOMER_REQUEST) {
                cancellationPayments.findSnapshot(orderId)?.let { snapshot ->
                    CancellationRefundRecoverySummary(
                        state = if (snapshot.paymentRecoveryRequired) "REQUESTED" else "NOT_REQUIRED",
                        approvedAmountKrw = snapshot.approvedAmountKrw,
                        succeededRefundAmountBeforeCancellationKrw =
                            snapshot.succeededRefundAmountBeforeCancellationKrw,
                        cancellationRequestedRefundAmountKrw = snapshot.requestedRefundAmountKrw,
                        remainingRefundableAmountKrw = snapshot.requestedRefundAmountKrw,
                        lastUpdatedAt = snapshot.updatedAt,
                    )
                } ?: CancellationRefundRecoverySummary(state = "NOT_REQUIRED")
            } else {
                null
            }
        return OrderResponse(
            orderId = order.id,
            storeId = order.storeId,
            state = order.state.name,
            reservationExpiresAt = order.reservationExpiresAt,
            paymentRecovery = paymentRecovery,
            paidAt = order.paidAt,
            acceptanceWarningAt = order.acceptanceWarningAt,
            acceptanceWarningRequestedAt = order.acceptanceWarningRequestedAt,
            acceptanceDeadlineAt = order.acceptanceDeadlineAt,
            acceptedAt = order.acceptedAt,
            rejectedAt = order.rejectedAt,
            preparingAt = order.preparingAt,
            readyAt = order.readyAt,
            completedAt = order.completedAt,
            cancelledAt = order.cancelledAt,
            cancellationCause = order.cancellationCause,
            cancellationReasonCode = order.cancellationReasonCode,
            rejectionReason = order.rejectionReason,
            lines =
                lines.map { line ->
                    OrderLineResponse(
                        orderLineId = line.id,
                        menuId = line.menuId,
                        menuName = line.menuName,
                        optionNames =
                            objectMapper
                                .readValue(line.optionNamesJson, Array<String>::class.java)
                                .toList(),
                        unitPriceKrw = line.unitPriceKrw,
                        quantity = line.quantity,
                        couponDiscountKrw = line.couponDiscountKrw,
                        pointsAppliedKrw = line.pointsAppliedKrw,
                        cashPaidKrw = line.cashPayableKrw,
                    )
                },
            subtotalKrw = order.subtotalKrw,
            couponDiscountKrw = order.couponDiscountKrw,
            pointsAppliedKrw = order.pointsAppliedKrw,
            payableKrw = order.payableKrw,
            currency = order.currency,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
        )
    }
}
