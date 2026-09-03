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

data class SupportOrderLineOverview(
    val sequence: Int,
    val menuName: String,
    val quantity: Long,
    val amountKrw: Long,
)

data class SupportOrderOverviewSnapshot(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val publicReference: String,
    val storeName: String,
    val state: SupportOrderState,
    val version: Long,
    val orderedAt: java.time.Instant,
    val pickupWindowStart: java.time.Instant,
    val pickupWindowEnd: java.time.Instant,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val paidAt: java.time.Instant?,
    val lines: List<SupportOrderLineOverview>,
)

interface OrderingSupportTimelineOperations {
    fun findTimelineFacts(query: SupportOwnerTimelineQuery): List<SupportOwnerTimelineFact>

    fun findOrderSnapshots(orderIds: Set<UUID>): List<SupportOrderSnapshot>

    fun findOrderOverviews(orderIds: Set<UUID>): List<SupportOrderOverviewSnapshot>
}
