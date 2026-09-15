package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

interface DemoPointProvisioning {
    /** Grants one 4,500 KRW sample-order lot. Caller serializes and journals the command. */
    fun grantSample(
        customerId: UUID,
        storeId: UUID,
        sourceReference: String,
        expiresAt: Instant,
        now: Instant,
    )
}
