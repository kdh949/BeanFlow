package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

/**
 * Customer-scoped, compact projection for Discovery's recent-store and recommendation reads.
 *
 * Ordering remains the only source of Order state. It deliberately returns no Order aggregate,
 * snapshot, customer data or Merchant display fields.
 */
interface CustomerRecentStoreQuery : OrderingApi {
    fun top(
        customerId: UUID,
        limit: Int,
    ): List<CustomerRecentStoreProjection>
}

data class CustomerRecentStoreProjection(
    val storeId: UUID,
    val lastOrderedAt: Instant,
)
