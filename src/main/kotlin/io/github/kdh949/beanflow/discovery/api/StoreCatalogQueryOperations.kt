package io.github.kdh949.beanflow.discovery.api

import com.fasterxml.jackson.annotation.JsonInclude
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
    /**
     * The complete current owner catalogue, including unavailable menus and options. A store with
     * more than 1,000 menus or 5,000 options fails with `DEPENDENCY_UNAVAILABLE` rather than
     * returning a partial list that a caller could mistake for complete.
     */
    fun listMenus(storeId: UUID): List<StoreMenuItemView>

    /**
     * Slots the store can actually be ordered from right now (ADR-076): those with `startsAt > now`
     * when the store is accepting orders and has pickup enabled. The list extends at most seven
     * days ahead and is complete only up to 1,000 matching slots; a 1,001st row fails explicitly
     * with `DEPENDENCY_UNAVAILABLE`. A store that cannot take pickup orders returns an empty list,
     * because every one of its slots would be rejected at order creation. An empty list is a
     * legitimate `200`; a persistence failure stays `503`.
     */
    fun listPickupSlots(
        storeId: UUID,
        now: Instant,
    ): List<StorePickupSlotView>
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StoreMenuItemView(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val currency: String,
    val available: Boolean,
    val displayCategory: String?,
    val description: String?,
    val options: List<StoreMenuItemOptionView>,
    val image: StorefrontImageView?,
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
