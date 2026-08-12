package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

enum class PostAcceptanceResolutionPointDisposition {
    RESTORED,
    PARTIALLY_RESTORED,
    SKIPPED_EXPIRED,
    NOT_ELIGIBLE,
}

data class RestorePostAcceptanceResolutionPointsCommand(
    val resolutionId: UUID,
    val orderId: UUID,
    val restoredAt: Instant,
    val sourceReference: String,
    val payloadHash: String,
)

data class PostAcceptanceResolutionPointResult(
    val resultId: UUID,
    val sourceReference: String,
    val disposition: PostAcceptanceResolutionPointDisposition,
    val restoredAmountKrw: Long,
    val replayed: Boolean,
)

interface PostAcceptanceResolutionPointOperations {
    /** Restores only still-valid original lots. It never creates a goodwill or replacement lot. */
    fun restore(command: RestorePostAcceptanceResolutionPointsCommand): PostAcceptanceResolutionPointResult
}
