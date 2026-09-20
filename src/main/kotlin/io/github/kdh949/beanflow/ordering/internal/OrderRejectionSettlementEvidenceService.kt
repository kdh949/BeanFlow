package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderRejectionSettlementEvidence
import io.github.kdh949.beanflow.ordering.api.OrderRejectionSettlementEvidenceOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class OrderRejectionSettlementEvidenceService(
    private val orders: OrderJpaRepository,
) : OrderRejectionSettlementEvidenceOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(orderId: UUID): OrderRejectionSettlementEvidence? =
        orders.findById(orderId).orElse(null)?.let { order ->
            OrderRejectionSettlementEvidence(
                orderId = order.id,
                customerId = order.customerId,
                state = order.state.name,
                aggregateVersion = order.version,
                rejectedAt = order.rejectedAt,
                rejectionCause = order.rejectionCause,
                rejectionActorType = order.rejectionActorType,
                rejectionEventId = order.rejectionEventId,
                rejectionTerminalVersion = order.rejectionTerminalVersion,
                acceptedAt = order.acceptedAt,
                preparingAt = order.preparingAt,
                readyAt = order.readyAt,
                completedAt = order.completedAt,
            )
        }
}
