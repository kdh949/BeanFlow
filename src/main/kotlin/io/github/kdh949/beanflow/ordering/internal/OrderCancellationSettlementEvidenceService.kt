package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationSettlementEvidence
import io.github.kdh949.beanflow.ordering.api.OrderCancellationSettlementEvidenceOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class OrderCancellationSettlementEvidenceService(
    private val orders: OrderJpaRepository,
) : OrderCancellationSettlementEvidenceOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(orderId: UUID): OrderCancellationSettlementEvidence? =
        orders.findById(orderId).orElse(null)?.let { order ->
            OrderCancellationSettlementEvidence(
                orderId = order.id,
                customerId = order.customerId,
                state = order.state.name,
                aggregateVersion = order.version,
                cancelledAt = order.cancelledAt,
                cancellationCause = order.cancellationCause,
                acceptedAt = order.acceptedAt,
                preparingAt = order.preparingAt,
                readyAt = order.readyAt,
                completedAt = order.completedAt,
            )
        }
}
