package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/**
 * Public store catalogue reads.
 *
 * Discovery owns the HTTP contract and the response projection. Merchant owns the menu catalogue
 * and Fulfillment owns pickup slot capacity; Discovery calls their public Query APIs and keeps no
 * replica of either. Both results are owner state at read time and neither is a reservation or
 * price guarantee.
 */
interface StoreCatalogQueryOperations : DiscoveryApi {
    fun listMenus(storeId: UUID): List<StoreMenuItemView>

    fun listPickupSlots(
        storeId: UUID,
        now: Instant,
    ): List<StorePickupSlotView>
}

data class StoreMenuItemView(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val currency: String,
    val available: Boolean,
    val options: List<StoreMenuItemOptionView>,
)

data class StoreMenuItemOptionView(
    val optionId: UUID,
    val name: String,
    val additionalPriceKrw: Long,
    val available: Boolean,
)

data class StorePickupSlotView(
    val pickupSlotId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val remainingCapacity: Long,
)
