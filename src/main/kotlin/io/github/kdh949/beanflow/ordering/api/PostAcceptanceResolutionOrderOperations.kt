package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class PostAcceptanceResolutionOrderFactState {
    PREPARING,
    READY,
    COMPLETED,
}

data class PostAcceptanceResolutionOrderFact(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val state: PostAcceptanceResolutionOrderFactState,
    val completedAt: Instant?,
    val payableKrw: Long,
    val currency: String,
    val version: Long,
)

interface PostAcceptanceResolutionOrderOperations {
    /** Reads the latest immutable lifecycle facts. This operation never mutates Order state. */
    fun find(orderId: UUID): PostAcceptanceResolutionOrderFact?
}
