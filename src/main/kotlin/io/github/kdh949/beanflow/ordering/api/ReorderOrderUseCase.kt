package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

data class ReorderOrderCommand(
    val customerId: UUID,
    val sourceOrderId: UUID,
    val pickupSlotId: UUID,
    val couponIssuanceId: UUID?,
    val pointsToUseKrw: Long,
)

interface ReorderOrderUseCase {
    fun reorder(
        idempotencyKey: String,
        command: ReorderOrderCommand,
    ): StoredHttpResponse
}
