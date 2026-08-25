package io.github.kdh949.beanflow.merchant.api

import java.math.BigDecimal
import java.util.UUID

/**
 * Merchant owns the searchable store profile. Discovery consumes this synchronous DTO projection
 * instead of Merchant entities, repositories or a persistent replica.
 *
 * The query coordinate is request-scoped input. Merchant uses it inside one read-only spatial
 * query and never writes it to a table, cache, audit record, event, log, trace or metric.
 */
interface StoreDiscoveryQueryOperations {
    /**
     * Returns pickup-capable stores inside [NearbyStoreProfileQuery.radiusMeters], ordered by
     * `(distanceMicrometers ASC, storeId ASC)`. At most [NearbyStoreProfileQuery.limit] rows are
     * returned; the caller asks for one extra row when it needs a next-page probe.
     *
     * "Pickup-capable" is owner state only: the store is accepting orders and has pickup enabled.
     * Whether a reservable slot actually exists is Fulfillment's answer
     * (`PickupAvailabilityQueryOperations`), so Merchant does not project it (ADR-103 2026-08-15
     * Amendment).
     */
    fun findPickupCapableStoresNear(query: NearbyStoreProfileQuery): List<NearbyStoreProfileProjection>

    /**
     * Current public display projections for an arbitrary set of stores.
     *
     * Discovery keeps customer-owned ordering (favorite creation order, recent order time) outside
     * Merchant, then uses this bulk read to hydrate only stores that remain publicly discoverable.
     * Missing ids deliberately have no projection: callers can reject a new target as 404 or omit
     * a stale historical reference without changing their own source record.
     */
    fun findVisibleStores(storeIds: Collection<UUID>): List<StoreDiscoveryDisplayProjection>

    /**
     * The number of stores the search index is expected to cover.
     *
     * Discovery divides its own indexed-store count by this to publish
     * `beanflow.discovery.search.index.store-row-presence.coverage`, so the denominator is read by
     * the module that owns the store table rather than by a Discovery query against it.
     */
    fun countIndexableStores(): Long
}

/**
 * A validated, canonical nearby query. Latitude and longitude are finite decimals already checked
 * against the public contract range, and [radiusMeters] is already inside `1..10000`.
 */
data class NearbyStoreProfileQuery(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val radiusMeters: Int,
    val after: NearbyStoreProfileCursor?,
    val limit: Int,
)

/** The last keyset tuple of the previous page. */
data class NearbyStoreProfileCursor(
    val distanceMicrometers: Long,
    val storeId: UUID,
)

/**
 * Current owner state for one store. [distanceMicrometers] is the canonical sort and cursor value;
 * the public contract exposes its floored integer-meter display value.
 *
 * There is deliberately no `pickupAvailable` here. Merchant knowing only `acceptingOrders` and
 * `pickupEnabled` is exactly how the weaker meaning used to leak into the nearby response; leaving
 * the field out makes re-deriving it impossible rather than merely discouraged.
 */
data class NearbyStoreProfileProjection(
    val storeId: UUID,
    val name: String,
    val distanceMicrometers: Long,
    val orderingAvailable: Boolean,
    val customerDisplay: StoreCustomerDisplayProjection,
    val imageThumbnailKey: String? = null,
)

/**
 * A non-spatial current display projection for a customer-owned store reference.
 *
 * [orderingAvailable] is only Merchant's owner state. Discovery combines it with Fulfillment's
 * reservable-slot batch answer before publishing `pickupAvailable` and `nextPickupWindow`.
 */
data class StoreDiscoveryDisplayProjection(
    val storeId: UUID,
    val name: String,
    val orderingAvailable: Boolean,
    val customerDisplay: StoreCustomerDisplayProjection,
    val imageThumbnailKey: String? = null,
)

data class StoreCustomerDisplayProjection(
    val addressLine: String?,
    val directionsHint: String?,
    val operatingHours: StoreWeeklyOperatingHours?,
)
