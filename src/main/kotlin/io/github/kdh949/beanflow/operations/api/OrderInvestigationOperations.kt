package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class OrderInvestigationState { PENDING_PAYMENT, PAID, ACCEPTED, PREPARING, READY, COMPLETED, REJECTED, EXPIRED, CANCELLED }

data class OrderInvestigationTarget(
    val orderId: UUID,
    val publicReference: String,
    val storeId: UUID,
    val storeName: String,
    val state: OrderInvestigationState,
    val createdAt: Instant,
)

/** Operations-owned outbound query port, implemented by Ordering. The caller owns authorization and Audit. */
interface OrderInvestigationOperations {
    fun findByReference(reference: String): OrderInvestigationTarget?

    fun findTargets(orderIds: Set<UUID>): Map<UUID, OrderInvestigationTarget>
}
