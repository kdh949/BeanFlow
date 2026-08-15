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
    fun top(
        customerId: UUID,
        limit: Int,
    ): List<CustomerRecentStoreProjection>
}

data class CustomerRecentStoreProjection(
    val storeId: UUID,
    val lastOrderedAt: Instant,
)
