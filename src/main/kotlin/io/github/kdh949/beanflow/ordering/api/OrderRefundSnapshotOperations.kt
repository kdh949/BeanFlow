package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

data class RefundableOrderLineSnapshot(
    val orderLineId: UUID,
    val lineSequence: Int,
    val unitPriceKrw: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val cashPayableKrw: Long,
)

data class RefundableOrderSnapshot(
    val orderId: UUID,
    val storeId: UUID,
    val state: String,
    val currency: String,
    val lines: List<RefundableOrderLineSnapshot>,
)

/** Typed, immutable Ordering boundary used while Payment owns the refund use case. */
interface OrderRefundSnapshotOperations {
    /**
     * Locks Order before Payment takes its row lock, preserving the global
     * Order -> Payment -> sorted allocation lock order.
     */
    fun lockRefundableSnapshot(orderId: UUID): RefundableOrderSnapshot
}
