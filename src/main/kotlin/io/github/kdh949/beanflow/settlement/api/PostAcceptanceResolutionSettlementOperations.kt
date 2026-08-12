package io.github.kdh949.beanflow.settlement.api

import java.time.Instant
import java.util.UUID

enum class PostAcceptanceResolutionSettlementResponsibility {
    STORE,
    SHARED,
}

data class CreatePostAcceptanceResolutionSettlementAdjustmentCommand(
    val resolutionId: UUID,
    val orderId: UUID,
    val storeId: UUID,
    val responsibility: PostAcceptanceResolutionSettlementResponsibility,
    val amountKrw: Long,
    val effectiveAt: Instant,
    val sourceReference: String,
    val payloadHash: String,
    val correlationId: String,
)

data class PostAcceptanceResolutionSettlementAdjustmentResult(
    val bindingId: UUID,
    val settlementAdjustmentId: UUID,
    val sourceReference: String,
    val amountKrw: Long,
    val replayed: Boolean,
)

interface PostAcceptanceResolutionSettlementOperations {
    /** Appends to a confirmed SettlementItem; no confirmed fact or prior Adjustment is overwritten. */
    fun create(command: CreatePostAcceptanceResolutionSettlementAdjustmentCommand): PostAcceptanceResolutionSettlementAdjustmentResult
}
