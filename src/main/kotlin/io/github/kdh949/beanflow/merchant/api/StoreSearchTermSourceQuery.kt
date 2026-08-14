package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

/**
 * The public store attributes Merchant owns and Discovery indexes.
 *
 * Discovery reads this port instead of Merchant tables, so the store name stays where ADR-020 put
 * it and the index never becomes a second source of truth for it.
 */
interface StoreSearchTermSourceQuery {
    /**
     * Store ids in ascending id order after [afterStoreId], at most [limit] of them.
     *
     * A keyset walk rather than an offset, so a rebuild that runs while stores are being created
     * cannot skip or repeat a store.
     */
    fun findStoreIdsAfter(
        afterStoreId: UUID?,
        limit: Int,
    ): List<UUID>

    /**
     * The indexable attributes of one store, or null if the store no longer exists.
     *
     * A store that exists without a searchable profile is a broken invariant (V34 asserts every
     * store has one), and it fails explicitly rather than being reported as a store with nothing
     * to index.
     */
    fun findSearchTermSource(storeId: UUID): StoreSearchTermSource?
}

data class StoreSearchTermSource(
    val storeId: UUID,
    val storeName: String,
    /** Menus currently on sale. ADR-103 searches available menu names only. */
    val availableMenus: List<StoreSearchMenuSource>,
)

data class StoreSearchMenuSource(
    val menuId: UUID,
    val name: String,
)
