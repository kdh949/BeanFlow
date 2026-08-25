package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/**
 * Marker for Discovery's public application surface.
 */
interface DiscoveryApi

/**
 * Nearby store search. Discovery owns request validation, cursor translation and the response
 * projection; Merchant owns the store profile and the spatial query.
 */
interface NearbyStoreQueryOperations : DiscoveryApi {
    fun search(command: SearchNearbyStoresCommand): NearbyStorePage
}

/**
 * Raw public query input.
 *
 * Coordinates and radius stay unparsed text until Discovery validates them, so an invalid value is
 * rejected by a message that never echoes the customer coordinate. The precise coordinate lives
 * only for the duration of this command and is never persisted, cached, audited, logged, traced or
 * used as a metric tag (BR-28, ADR-020).
 */
data class SearchNearbyStoresCommand(
    val latitude: String?,
    val longitude: String?,
    val radiusMeters: String?,
    val pickupAvailable: String?,
    val cursor: String?,
    val limit: String?,
    val now: Instant,
)

data class NearbyStorePage(
    val items: List<NearbyStoreView>,
    val nextCursor: String?,
)

/**
 * One public nearby result. [distanceMeters] is the floored integer-meter display value of the
 * canonical micrometer distance and is never reused as a pagination key.
 *
 * [pickupAvailable] is the Fulfillment batch judgement — a reservable slot exists inside the
 * seven-day window — and means exactly what the same field means on `GET /stores/search`
 * (ADR-103 2026-08-15 Amendment). It is a read-time projection, so a concurrent order can consume
 * the last seat immediately afterwards.
 */
data class NearbyStoreView(
    val storeId: UUID,
    val name: String,
    val distanceMeters: Long,
    val orderingAvailable: Boolean,
    val pickupAvailable: Boolean,
    val nextPickupWindow: NextPickupWindowView?,
    val customerDisplay: CustomerStoreDisplayView,
    val image: StorefrontImageView? = null,
)
