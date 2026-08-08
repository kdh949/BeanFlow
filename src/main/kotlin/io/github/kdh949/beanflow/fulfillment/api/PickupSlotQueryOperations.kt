package io.github.kdh949.beanflow.fulfillment.api

import java.time.Instant
import java.util.UUID

/**
 * Fulfillment owns PickupSlot capacity. Discovery consumes this synchronous DTO projection instead
 * of Fulfillment entities or repositories.
 *
 * The projection is current owner state, not a reservation guarantee. A concurrent order can change
 * remaining capacity immediately after the read, and reserving still goes through
 * [PickupReservationOperations.reserve], which locks the slot and re-checks capacity.
 */
interface PickupSlotQueryOperations {
    /**
     * Returns the store's slots that have not started yet, ordered by `(startsAt, pickupSlotId)`.
     *
     * The lower bound is exactly the bound [PickupReservationOperations.reserve] enforces (BR-05,
     * ADR-076): a slot is listed while `startsAt > now` and stops being listed at the same instant
     * it stops being reservable. The list also reaches only seven days ahead, so the response is
     * bounded without truncating: everything inside the window is returned in full. A slot with no
     * remaining capacity is still returned with `remainingCapacity = 0`.
     *
     * @throws io.github.kdh949.beanflow.shared.api.DomainFailure with `DEPENDENCY_UNAVAILABLE` when
     * persistence fails. A dependency failure is never collapsed into an empty list.
     */
    fun listOpenSlots(
        storeId: UUID,
        now: Instant,
    ): List<PickupSlotView>
}

data class PickupSlotView(
    val pickupSlotId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val remainingCapacity: Long,
)
