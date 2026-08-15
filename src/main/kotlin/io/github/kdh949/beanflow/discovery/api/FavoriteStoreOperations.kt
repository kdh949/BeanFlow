package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/** Customer-owned favorite-store commands and current display query. */
interface FavoriteStoreOperations : DiscoveryApi {
    fun list(
        customerId: UUID,
        now: Instant,
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
