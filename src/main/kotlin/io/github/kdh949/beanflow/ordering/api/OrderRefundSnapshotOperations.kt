package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

data class RefundableOrderLineSnapshot(
    val orderLineId: UUID,
    val lineSequence: Int,
    val menuName: String,
    val unitPriceKrw: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val cashPayableKrw: Long,
)

data class RefundableOrderSnapshot(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val state: String,
    val completedAt: Instant?,
    val aggregateVersion: Long,
    val currency: String,
    val lines: List<RefundableOrderLineSnapshot>,
)

data class RefundResultOrderSnapshot(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val state: String,
    val completedAt: Instant?,
    val aggregateVersion: Long,
    val currency: String,
)

/** Typed, immutable Ordering boundary used while Payment owns the refund use case. */
interface OrderRefundSnapshotOperations {
    /**
     * Locks Order before Payment takes its row lock, preserving the global
     * Order -> Payment -> sorted allocation lock order.
     */
    fun lockRefundableSnapshot(orderId: UUID): RefundableOrderSnapshot

    /**
     * Reads the same snapshot without any lock, for the refund preview
     * projection. A preview never reserves units, so its amounts are only a
     * projection of this instant.
     */
    fun readRefundableSnapshot(orderId: UUID): RefundableOrderSnapshot

    /** Locks the Order before Payment records a Provider result, regardless of current Order state. */
    fun lockResultSnapshot(orderId: UUID): RefundResultOrderSnapshot
}
