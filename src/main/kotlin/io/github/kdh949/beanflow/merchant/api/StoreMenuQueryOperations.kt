package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

/**
 * Merchant owns the menu catalogue. Discovery consumes this synchronous DTO projection instead of
 * Merchant entities or repositories.
 *
 * Every value is the owner state at read time. It is never mixed with a past Order price snapshot
 * and it is not a purchase guarantee: availability and price can change immediately after the read,
 * and order creation re-quotes from the owner state.
 */
interface StoreMenuQueryOperations {
    /**
     * Returns the store's menus ordered by `(name, menuId)` with their options ordered by
     * `(name, optionId)`.
     *
     * `available` carries the current owner flag rather than filtering the list, because the public
     * contract requires the flag on every menu and option. A menu of another store is never
     * returned for [storeId].
     *
     * The catalogue has a published bound of 1,000 menus and 5,000 options. A store beyond it fails
     * the read explicitly; the list is never truncated, because a caller cannot distinguish a
     * truncated catalogue from a complete one.
     *
     * @throws io.github.kdh949.beanflow.shared.api.DomainFailure with `RESOURCE_NOT_FOUND` when the
     * store does not exist, or `DEPENDENCY_UNAVAILABLE` when persistence fails or the catalogue
     * exceeds the published bound. A dependency failure is never collapsed into absence or an empty
     * list.
     */
    fun listMenus(storeId: UUID): List<StoreMenuView>
}

data class StoreMenuView(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
    val options: List<StoreMenuOptionView>,
    val imageThumbnailKey: String?,
)

data class StoreMenuOptionView(
    val optionId: UUID,
    val name: String,
    val additionalPriceKrw: Long,
    val available: Boolean,
)
