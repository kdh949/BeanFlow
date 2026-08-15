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
     */
    fun findPickupCapableStoresNear(query: NearbyStoreProfileQuery): List<NearbyStoreProfileProjection>

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
 */
data class NearbyStoreProfileProjection(
    val storeId: UUID,
    val name: String,
    val distanceMicrometers: Long,
    val open: Boolean,
    val pickupAvailable: Boolean,
)
