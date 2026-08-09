package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

interface StorePolicyScopeOperations {
    /**
     * Verifies the authoritative Merchant-owned Store identity.
     *
     * @throws io.github.kdh949.beanflow.shared.api.DomainFailure with RESOURCE_NOT_FOUND or
     * DEPENDENCY_UNAVAILABLE. A dependency failure is never collapsed into absence.
     */
    fun requireExisting(storeId: UUID)

    /**
     * Verifies Store identity and answers whether the Store can currently take pickup orders, which
     * is `acceptingOrders && pickupEnabled` — the same condition order creation enforces.
     *
     * @throws io.github.kdh949.beanflow.shared.api.DomainFailure with RESOURCE_NOT_FOUND or
     * DEPENDENCY_UNAVAILABLE. A dependency failure is never collapsed into `false`.
     */
    fun pickupOrderingAvailable(storeId: UUID): Boolean
}
