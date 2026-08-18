package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class PaymentOrderReferenceProjection(
    private val orders: OrderJpaRepository,
) {
    @Transactional(readOnly = true)
    fun resolveOwned(
        customerId: UUID,
        orderId: UUID,
    ): String {
        val order = order(orderId)
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment order ownership is inconsistent")
        }
        return order.publicReference
    }

    @Transactional(readOnly = true)
    fun resolve(orderId: UUID): String = order(orderId).publicReference

    private fun order(orderId: UUID): OrderEntity =
        orders.findById(orderId).orElse(null)
            ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment order is missing")
}
