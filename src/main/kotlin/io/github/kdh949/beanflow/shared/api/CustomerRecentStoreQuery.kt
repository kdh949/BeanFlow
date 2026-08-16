package io.github.kdh949.beanflow.shared.api

import java.time.Instant
import java.util.UUID

/**
 * Customer-scoped, compact projection for Discovery's recent-store and recommendation reads.
 *
 * Ordering remains the only source of Order state and implements this contract. The contract is
 * shared only to avoid a prohibited Discovery-to-Ordering module dependency; it returns no Order
 * aggregate, snapshot, customer data or Merchant display fields (MD-2026-028).
 */
interface CustomerRecentStoreQuery {
    /**
     * [after] continues from a previously returned projection in the same
     * `(lastOrderedAt DESC, storeId ASC)` order.
     *
     * Discovery needs it because a store the customer really did order from can have lost its public
     * profile since. Without continuation, such a store consumes one of the caller's limit slots and
     * silently hides an eligible store behind it, with no cursor on the endpoint to reach past it.
     */
    fun top(
        customerId: UUID,
        limit: Int,
        after: CustomerRecentStoreCursor? = null,
    ): List<CustomerRecentStoreProjection>
}

data class CustomerRecentStoreProjection(
    val storeId: UUID,
    val lastOrderedAt: Instant,
)

data class CustomerRecentStoreCursor(
    val lastOrderedAt: Instant,
    val storeId: UUID,
)
