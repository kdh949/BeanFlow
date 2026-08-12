package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderFact
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class GoodwillCompensationOrderQueryService(
    private val orders: OrderJpaRepository,
) : GoodwillCompensationOrderOperations {
    @Transactional(readOnly = true)
    override fun find(orderId: UUID): GoodwillCompensationOrderFact? =
        orders.findById(orderId).orElse(null)?.let { order ->
            GoodwillCompensationOrderFact(
                orderId = order.id,
                customerId = order.customerId,
                storeId = order.storeId,
                payableKrw = order.payableKrw,
                currency = order.currency,
                state = order.state.name,
                version = order.version,
            )
        }
}
