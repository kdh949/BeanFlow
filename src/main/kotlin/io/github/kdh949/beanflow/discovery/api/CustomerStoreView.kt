package io.github.kdh949.beanflow.discovery.api

import java.util.UUID

/**
 * The compact public store representation shared by favorites, recent stores and recommendations.
 *
 * `distanceMeters` is absent for favorites and recent stores; it is only populated by a nearby
 * recommendation path that received a coordinate pair.
 */
data class CustomerStoreView(
    val storeId: UUID,
    val name: String,
    val pickupAvailable: Boolean,
    val distanceMeters: Long? = null,
)
