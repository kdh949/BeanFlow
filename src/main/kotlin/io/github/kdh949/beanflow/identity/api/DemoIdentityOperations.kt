package io.github.kdh949.beanflow.identity.api

import java.time.Instant
import java.util.UUID

/** New, passwordless, time-bounded sandbox identities only. Never upgrades an existing account. */
interface DemoIdentityOperations {
    fun provision(
        customerId: UUID,
        merchantId: UUID,
        storeId: UUID,
        now: Instant,
        expiresAt: Instant,
    )

    fun end(
        customerId: UUID,
        merchantId: UUID,
        now: Instant,
    )
}

/** Applied to every order creation, including reorders. Ordinary customers have no store constraint. */
interface CustomerOrderingAccess {
    fun requireStore(
        customerId: UUID,
        storeId: UUID,
    )
}
