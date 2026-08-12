package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

enum class PostAcceptanceResolutionOrderState {
    PREPARING,
    READY,
    COMPLETED,
}

enum class PostAcceptanceResolutionRefundState {
    REQUESTED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
}

data class RequestPostAcceptanceResolutionRefundCommand(
    val resolutionId: UUID,
    val actorId: UUID,
    val orderId: UUID,
    val amountKrw: Long,
    val orderState: PostAcceptanceResolutionOrderState,
    val orderCompletedAt: Instant?,
    val orderVersion: Long,
    val sourceReference: String,
    val payloadHash: String,
    val correlationId: String,
    val now: Instant,
)

data class PostAcceptanceResolutionRefundView(
    val refundId: UUID,
    val orderId: UUID,
    val amountKrw: Long,
    val state: PostAcceptanceResolutionRefundState,
    val sourceReference: String,
    val updatedAt: Instant,
    val replayed: Boolean,
)

data class SchedulePostAcceptanceResolutionRefundReconciliationCommand(
    val resolutionId: UUID,
    val refundId: UUID,
    val sourceReference: String,
    val now: Instant,
)

interface PostAcceptanceResolutionPaymentOperations {
    fun request(command: RequestPostAcceptanceResolutionRefundCommand): PostAcceptanceResolutionRefundView

    fun findBySourceReference(sourceReference: String): PostAcceptanceResolutionRefundView?

    /** Claims in Payment's transaction, calls the Provider outside it, then records the result in a new transaction. */
    fun execute(
        refundId: UUID,
        now: Instant,
    ): PostAcceptanceResolutionRefundView

    fun scheduleReconciliation(
        command: SchedulePostAcceptanceResolutionRefundReconciliationCommand,
    ): PostAcceptanceResolutionRefundView
}
