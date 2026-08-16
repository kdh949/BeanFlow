package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderRefundSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.RefundResultOrderSnapshot
import io.github.kdh949.beanflow.ordering.api.RefundableOrderLineSnapshot
import io.github.kdh949.beanflow.ordering.api.RefundableOrderSnapshot
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class OrderRefundSnapshotService(
    private val orderRepository: OrderJpaRepository,
    private val lineRepository: OrderLineJpaRepository,
) : OrderRefundSnapshotOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockRefundableSnapshot(orderId: UUID): RefundableOrderSnapshot =
        refundableSnapshot(
            orderRepository.findLockedById(orderId)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Order was not found"),
        )

    @Transactional(readOnly = true)
    override fun readRefundableSnapshot(orderId: UUID): RefundableOrderSnapshot =
        refundableSnapshot(
            orderRepository.findById(orderId).orElse(null)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Order was not found"),
        )

    private fun refundableSnapshot(order: OrderEntity): RefundableOrderSnapshot {
        if (order.state !in REFUNDABLE_STATES) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Order state does not allow an item refund")
        }
        val lines = lineRepository.findAllByOrderIdOrderByLineSequence(order.id)
        if (lines.isEmpty()) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Refundable OrderLine snapshot is missing")
        }
        return RefundableOrderSnapshot(
            orderId = order.id,
            customerId = order.customerId,
            storeId = order.storeId,
            state = order.state.name,
            completedAt = order.completedAt,
            aggregateVersion = order.version,
            currency = order.currency,
            lines =
                lines.map {
                    RefundableOrderLineSnapshot(
                        orderLineId = it.id,
                        lineSequence = it.lineSequence,
                        menuName = it.menuName,
                        unitPriceKrw = it.unitPriceKrw,
                        quantity = it.quantity,
                        grossKrw = it.grossKrw,
                        couponDiscountKrw = it.couponDiscountKrw,
                        pointsAppliedKrw = it.pointsAppliedKrw,
                        cashPayableKrw = it.cashPayableKrw,
                    )
                },
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockResultSnapshot(orderId: UUID): RefundResultOrderSnapshot {
        val order =
            orderRepository.findLockedById(orderId)
                ?: fail(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        return RefundResultOrderSnapshot(
            orderId = order.id,
            customerId = order.customerId,
            storeId = order.storeId,
            state = order.state.name,
            completedAt = order.completedAt,
            aggregateVersion = order.version,
            currency = order.currency,
        )
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        val REFUNDABLE_STATES =
            setOf(
                OrderState.PAID,
                OrderState.ACCEPTED,
                OrderState.PREPARING,
                OrderState.READY,
                OrderState.COMPLETED,
            )
    }
}
