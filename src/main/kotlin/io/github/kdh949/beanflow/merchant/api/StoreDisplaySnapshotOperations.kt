package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class StoreDisplaySnapshot(
    val storeId: UUID,
    val name: String,
)

interface StoreDisplaySnapshotOperations {
    /**
     * Returns the owner-verified display name used for an immutable Order snapshot.
     * Missing, blank or mismatched owner data fails the caller transaction.
     */
    fun require(storeId: UUID): StoreDisplaySnapshot
}
