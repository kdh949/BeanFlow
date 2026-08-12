package io.github.kdh949.beanflow.promotion.api

import java.time.Instant
import java.util.UUID

enum class PostAcceptanceResolutionCouponDisposition {
    RESTORED,
    SKIPPED_EXPIRED,
    NOT_ELIGIBLE,
}

data class RestorePostAcceptanceResolutionCouponCommand(
    val resolutionId: UUID,
    val orderId: UUID,
    val restoredAt: Instant,
    val sourceReference: String,
    val payloadHash: String,
)

data class PostAcceptanceResolutionCouponResult(
    val resultId: UUID,
    val sourceReference: String,
    val disposition: PostAcceptanceResolutionCouponDisposition,
    val replayed: Boolean,
)

interface PostAcceptanceResolutionCouponOperations {
    /** Restores only the original issuance while it remains valid. It never issues a goodwill coupon. */
    fun restore(command: RestorePostAcceptanceResolutionCouponCommand): PostAcceptanceResolutionCouponResult
}
