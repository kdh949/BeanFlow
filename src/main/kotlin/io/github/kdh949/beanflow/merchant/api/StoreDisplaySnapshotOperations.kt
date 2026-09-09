package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class StoreDisplaySnapshot(
    val storeId: UUID,
    val name: String,
    val storeVersion: Long = 0,
)

data class StoreDisplaySnapshotPage(
    val stores: List<StoreDisplaySnapshot>,
    val nextName: String?,
    val nextStoreId: UUID?,
)

interface StoreDisplaySnapshotOperations {
    /**
     * Returns the owner-verified display name used for an immutable Order snapshot.
     * Missing, blank or mismatched owner data fails the caller transaction.
     */
    fun require(storeId: UUID): StoreDisplaySnapshot

    /** Returns verified store display snapshots ordered by name and store ID after the optional keyset boundary. */
    fun list(
        afterName: String?,
        afterStoreId: UUID?,
        limit: Int,
    ): StoreDisplaySnapshotPage
}
