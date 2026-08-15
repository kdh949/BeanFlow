package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.discovery.api.NearbyStoreQueryOperations
import io.github.kdh949.beanflow.discovery.api.RecentStoreOperations
import io.github.kdh949.beanflow.discovery.api.RecommendationReason
import io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand
import io.github.kdh949.beanflow.discovery.api.StoreRecommendation
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationCommand
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationList
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Merges the three accepted baseline signals in policy order. Each source remains authoritative
 * in its own context; the first source that contributes a store owns the public reason.
 */
@Service
internal class StoreRecommendationService(
    private val favorites: FavoriteStoreOperations,
    private val recentStores: RecentStoreOperations,
    private val nearbyStores: NearbyStoreQueryOperations,
    private val nearbyValidation: NearbyStoreQueryValidation,
) : StoreRecommendationOperations {
    @Transactional(readOnly = true)
    override fun list(
        customerId: UUID,
        command: StoreRecommendationCommand,
    ): StoreRecommendationList {
        val limit = CompactStoreLimit.parse(command.limit)
        val nearby = prepareNearby(command, limit)
        val merged = LinkedHashMap<UUID, StoreRecommendation>(limit)

        append(merged, favorites.list(customerId, command.now), RecommendationReason.FAVORITE, limit)
        append(merged, recentStores.list(customerId, limit.toString(), command.now), RecommendationReason.RECENT, limit)

        if (nearby != null) {
            val page = nearbyStores.search(nearby)
            page.items.forEach { item ->
                val store =
                    CustomerStoreView(
                        storeId = item.storeId,
                        name = item.name,
                        pickupAvailable = item.pickupAvailable,
                        distanceMeters = item.distanceMeters,
                    )
                if (merged.size < limit) {
                    merged.putIfAbsent(item.storeId, StoreRecommendation(store, RecommendationReason.NEARBY))
                }
            }
        }

        return StoreRecommendationList(merged.values.toList())
    }

    private fun prepareNearby(
        command: StoreRecommendationCommand,
        limit: Int,
    ): SearchNearbyStoresCommand? {
        val hasLatitude = command.latitude != null
        val hasLongitude = command.longitude != null
        if (hasLatitude != hasLongitude) {
            invalid("Latitude and longitude must be provided together")
        }
        if (!hasLatitude) {
            if (command.radiusMeters != null) invalid("Radius requires latitude and longitude")
            return null
        }
        val radiusMeters = command.radiusMeters ?: DEFAULT_NEARBY_RADIUS_METERS

        // Reuse the nearby validator so coordinate grammar, range and radius semantics cannot
        // drift between the two customer endpoints. This is a read-only, no-DB validation call.
        nearbyValidation.prepare(
            SearchNearbyStoresCommand(
                latitude = command.latitude,
                longitude = command.longitude,
                radiusMeters = radiusMeters,
                pickupAvailable = null,
                cursor = null,
                limit = NEARBY_LIMIT.toString(),
                now = command.now,
            ),
        )
        return SearchNearbyStoresCommand(
            latitude = command.latitude,
            longitude = command.longitude,
            radiusMeters = radiusMeters,
            pickupAvailable = null,
            cursor = null,
            limit = maxOf(limit, NEARBY_LIMIT).toString(),
            now = command.now,
        )
    }

    private fun append(
        merged: LinkedHashMap<UUID, StoreRecommendation>,
        stores: List<CustomerStoreView>,
        reason: RecommendationReason,
        limit: Int,
    ) {
        stores.forEach { store ->
            if (merged.size < limit) {
                merged.putIfAbsent(store.storeId, StoreRecommendation(store, reason))
            }
        }
    }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        // Nearby allows a larger page so favorites/recent can be de-duplicated without starving
        // the final compact recommendation list. The endpoint's own output remains at most 20.
        const val NEARBY_LIMIT = 100
        const val DEFAULT_NEARBY_RADIUS_METERS = "3000"
    }
}
