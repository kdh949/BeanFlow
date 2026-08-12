package io.github.kdh949.beanflow.ordering.api

import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineFact
import io.github.kdh949.beanflow.shared.api.SupportOwnerTimelineQuery
import java.util.UUID

enum class SupportOrderState {
    PENDING_PAYMENT,
    PAID,
    ACCEPTED,
    PREPARING,
    READY,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
}

data class SupportOrderSnapshot(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val state: SupportOrderState,
    val version: Long,
)

interface OrderingSupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>

    fun findOrderSnapshots(orderIds: Set<UUID>): List<SupportOrderSnapshot>
}
