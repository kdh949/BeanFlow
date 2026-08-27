package io.github.kdh949.beanflow.shared.api

import java.util.UUID

/** Inventory-owned validation port used without exposing Inventory persistence to Merchant. */
interface SellableUnitValidationOperations {
    fun requireOwnedByStore(
        storeId: UUID,
        sellableUnitIds: Set<UUID>,
    )
}
