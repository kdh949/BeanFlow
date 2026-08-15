package io.github.kdh949.beanflow.shared.api

import java.util.UUID

/**
 * Rebuilds the Discovery-owned search terms from current Merchant store and menu source data.
 *
 * The caller owns authorization, command replay and audit. This small cross-context contract is
 * in `shared/api` so Operations can invoke the Discovery implementation without a reverse module
 * dependency; it does not transfer index or source-data ownership from Discovery (MD-2026-028).
 */
interface StoreSearchIndexRebuildOperations {
    fun rebuildAll(): StoreSearchIndexRebuildResult
}

/** A completed rebuild pass, including any stores that could not be rebuilt. */
data class StoreSearchIndexRebuildResult(
    val indexedStoreCount: Int,
    val skippedStoreCount: Int,
    val failedStoreIds: List<UUID>,
) {
    val complete: Boolean get() = failedStoreIds.isEmpty()
}
