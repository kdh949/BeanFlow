package io.github.kdh949.beanflow.shared.api

import java.util.UUID

/** Ordering-owned immutable display projection, scoped to the customer in the database query. */
interface CustomerOrderDisplayQuery {
    /** At most one ledger page (100 distinct IDs). Missing IDs never bypass customer ownership. */
    fun find(
        customerId: UUID,
        orderIds: Set<UUID>,
    ): List<CustomerOrderDisplay>
}

data class CustomerOrderDisplay(
    val orderId: UUID,
    val publicReference: String,
    val storeName: String,
    val firstMenuName: String,
)
