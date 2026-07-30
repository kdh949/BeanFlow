package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.PaymentPreparation
import io.github.kdh949.beanflow.payment.api.PrepareExternalPaymentCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

internal sealed interface OrderPaymentPreparation {
    data class Ready(
        val payment: PaymentPreparation,
    ) : OrderPaymentPreparation

    data object Expired : OrderPaymentPreparation
}

@Service
internal class PaymentPreparationTransaction(
    private val orderRepository: OrderJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val paymentOperations: ExternalPaymentOperations,
) {
    @Transactional(readOnly = true)
    fun requestedAmount(
        customerId: UUID,
        orderId: UUID,
    ): Long {
        val order =
            orderRepository.findById(orderId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        return order.payableKrw
    }

    @Transactional
    fun prepare(command: PrepareExternalPaymentCommand): OrderPaymentPreparation {
        val order =
            orderRepository.findLockedById(command.orderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != command.actorId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        if (order.payableKrw <= 0) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order has no external payable amount")
        }
        val existing = paymentOperations.existing(command)
        val deadline = order.reservationExpiresAt
        if (order.state == OrderState.PENDING_PAYMENT && deadline != null && !command.now.isBefore(deadline)) {
            expiryUseCase.expireIfDue(command.orderId, command.now)
            if (existing != null) {
                return OrderPaymentPreparation.Ready(existing)
            }
            return OrderPaymentPreparation.Expired
        }
        if (existing != null) {
            return OrderPaymentPreparation.Ready(existing)
        }
        if (order.state != OrderState.PENDING_PAYMENT || order.payableKrw <= 0 || deadline == null) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for external payment")
        }
        if (command.requestedAmountKrw != order.payableKrw) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order payment amount changed")
        }
        return OrderPaymentPreparation.Ready(paymentOperations.prepare(command))
    }
}
