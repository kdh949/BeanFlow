package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/**
 * A customer may hold at most this many favorite stores.
 *
 * Without a cap, one customer can favorite every store on the platform and make a single GET
 * hydrate that many ids through Merchant and Fulfillment. The bound belongs to the contract, not
 * only to the query, because the write path is what has to refuse.
 */
const val MAX_FAVORITE_STORES: Int = 200

/** Customer-owned favorite-store commands and current display query. */
interface FavoriteStoreOperations : DiscoveryApi {
    /** [limit] bounds the read; callers that only need a few stores must not hydrate all of them. */
    fun list(
        customerId: UUID,
        now: Instant,
        limit: Int,
    ): List<CustomerStoreView>

    fun add(
        customerId: UUID,
        storeId: UUID,
        now: Instant,
    )

    fun remove(
        customerId: UUID,
        storeId: UUID,
    )
}
