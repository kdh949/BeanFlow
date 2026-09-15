package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

data class DemoStoreCatalog(
    val storeId: UUID,
    val sampleMenuId: UUID,
)

/** Creates a fresh sandbox store only; never accepts an existing store ID. */
interface DemoStoreProvisioning {
    fun create(
        workspaceId: UUID,
        now: Instant,
    ): DemoStoreCatalog

    fun close(
        storeId: UUID,
        now: Instant,
    )
}
