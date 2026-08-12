package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.PostAcceptanceResolutionOrderFact
import io.github.kdh949.beanflow.ordering.api.PostAcceptanceResolutionOrderFactState
import io.github.kdh949.beanflow.ordering.api.PostAcceptanceResolutionOrderOperations
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class PostAcceptanceResolutionOrderQueryService(
    private val orders: OrderJpaRepository,
) : PostAcceptanceResolutionOrderOperations {
    @Transactional(readOnly = true)
    override fun find(orderId: UUID): PostAcceptanceResolutionOrderFact? {
        val order = orders.findById(orderId).orElse(null) ?: return null
        val state =
            when (order.state) {
                OrderState.PREPARING -> PostAcceptanceResolutionOrderFactState.PREPARING
                OrderState.READY -> PostAcceptanceResolutionOrderFactState.READY
                OrderState.COMPLETED -> PostAcceptanceResolutionOrderFactState.COMPLETED
                else -> return null
            }
        check((state == PostAcceptanceResolutionOrderFactState.COMPLETED) == (order.completedAt != null)) {
            "Order completion fact is inconsistent"
        }
        return PostAcceptanceResolutionOrderFact(
            orderId = order.id,
            customerId = order.customerId,
            storeId = order.storeId,
            state = state,
            completedAt = order.completedAt,
            payableKrw = order.payableKrw,
            currency = order.currency,
            version = order.version,
        )
    }
}
