package io.github.kdh949.beanflow.fulfillment.api

import java.time.Instant
import java.util.UUID

/**
 * Batch pickup availability for a page of Discovery candidates (ADR-103 2026-08-15 Amendment).
 *
 * Discovery never joins `fulfillment_pickup_slot` from its own query. It hands the ordered
 * candidate ids to this port instead, and Fulfillment answers for the whole page in one statement,
 * so the number of availability queries does not grow with the number of candidates.
 *
 * The answer is the same judgement [PickupSlotQueryOperations.listOpenSlots] publishes, collapsed
 * to existence: inside the seven-day window a slot counts when `startsAt > now` and
 * `capacity - reservedCount - confirmedCount > 0`. It is a read-time projection, not a reservation
 * guarantee — a concurrent order can consume the last seat immediately afterwards and order
 * creation still fails with `PICKUP_SLOT_FULL`.
 */
interface PickupAvailabilityQueryOperations : FulfillmentApi {
    /**
     * Returns the subset of [storeIds] that has at least one reservable slot inside the window.
     *
     * A store with no slot row is simply absent from the result. An empty [storeIds] returns an
     * empty set without touching the database.
     *
     * @throws io.github.kdh949.beanflow.shared.api.DomainFailure with `DEPENDENCY_UNAVAILABLE` when
     * persistence fails or a candidate store owns a slot whose counters are corrupted. A corrupted
     * counter is never collapsed into "not available": that would publish a plausible-looking
     * closed store instead of an explicit failure.
     */
    fun findStoresWithAvailableSlots(
        storeIds: Collection<UUID>,
        now: Instant,
    ): Set<UUID>
}
