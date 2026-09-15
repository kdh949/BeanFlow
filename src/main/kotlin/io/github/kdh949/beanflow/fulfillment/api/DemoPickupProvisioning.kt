package io.github.kdh949.beanflow.fulfillment.api

import java.time.Instant
import java.util.UUID

interface DemoPickupProvisioning {
    /** Creates new slots for a fresh workspace; the first starts after the workspace access deadline. */
    fun create(
        storeId: UUID,
        expiresAt: Instant,
    ): UUID
}
