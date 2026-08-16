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

/**
 * What one rebuild pass did.
 *
 * Failures are reported as store ids rather than a count so that a partial rebuild can never be
 * read as a complete one, and so the operator command can name what to retry.
 *
 * `targetStoreCount` is the size of the id snapshot taken before the first chunk. Completeness is
 * judged against that snapshot, not against the live table: a store inserted while the pass runs
 * is out of scope for this pass rather than a silent omission from a "complete" one. It stays out
 * of the published HTTP response, which reports only what the pass did.
 */
data class StoreSearchIndexRebuildResult(
    val targetStoreCount: Int,
    val indexedStoreCount: Int,
    val skippedStoreCount: Int,
    val failedStoreIds: List<UUID>,
) {
    /** True only when every store id in this pass's initial target snapshot succeeded. */
    val completeSnapshot: Boolean get() = failedStoreIds.isEmpty()
}
