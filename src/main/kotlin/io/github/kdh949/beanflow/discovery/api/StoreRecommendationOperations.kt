package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/** Customer-scoped deterministic merge of favorite, recent and optional nearby stores. */
interface StoreRecommendationOperations : DiscoveryApi {
    fun list(
        customerId: UUID,
        command: StoreRecommendationCommand,
    ): StoreRecommendationList
}

data class StoreRecommendationCommand(
    val latitude: String?,
    val longitude: String?,
    val radiusMeters: String?,
    val limit: String?,
    val now: Instant,
)

data class StoreRecommendationList(
    val items: List<StoreRecommendation>,
)

data class StoreRecommendation(
    val store: CustomerStoreView,
    val reason: RecommendationReason,
)

enum class RecommendationReason {
    FAVORITE,
    RECENT,
    NEARBY,
}
