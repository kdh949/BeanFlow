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
     * The immutable target set for one rebuild pass, captured before that pass starts writing.
     *
     * Store ids are UUIDs and have no creation order, so a live keyset walk can silently miss an
     * id created after a prior page. A completed rebuild therefore means this captured set was
     * processed; stores created afterwards belong to their own synchronous write or a later pass.
     */
    fun findRebuildTargetStoreIds(): List<UUID>

    /**
     * All source facts that the store-name and menu-name index rows must mirror.
     *
     * Discovery uses this projection only to reconcile its derived index. Merchant remains the
     * owner of the source rows and Discovery does not read Merchant tables directly.
     */
    fun findAllSearchTermSources(): List<StoreSearchTermSource>

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
