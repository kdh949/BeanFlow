package io.github.kdh949.beanflow.discovery.api

import java.time.Instant
import java.util.UUID

/** Current-display hydration of the customer's eligible recent Order stores. */
interface RecentStoreOperations : DiscoveryApi {
    /**
     * [rawLimit] is validated at Discovery's HTTP boundary because the target contract accepts an
     * optional compact-list query parameter. Ordering receives only the bounded integer limit.
     */
    fun list(
        customerId: UUID,
        rawLimit: String?,
        now: Instant,
    ): List<CustomerStoreView>
}
