package io.github.kdh949.beanflow.fulfillment.api

import java.time.Instant
import java.util.UUID

data class PickupQuoteSnapshot(
    val pickupSlotId: UUID,
    val storeId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Long,
    val reservedCount: Long,
    val confirmedCount: Long,
    val version: Long,
)

interface PickupQuoteOperations {
    fun inspect(
        storeId: UUID,
        pickupSlotId: UUID,
    ): PickupQuoteSnapshot

    fun lockForOrderCreation(
        storeId: UUID,
        pickupSlotId: UUID,
    ): PickupQuoteSnapshot
}
