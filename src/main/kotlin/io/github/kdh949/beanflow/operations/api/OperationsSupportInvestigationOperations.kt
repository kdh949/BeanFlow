package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class OperationsSupportInvestigationDecision {
    APPROVE,
    DENY,
    RETURN_FOR_REVISION,
    ESCALATE,
}

enum class OperationsSupportInvestigationState {
    OPEN,
    APPROVED,
    DENIED,
    RETURNED,
    ESCALATED,
    EXPIRED,
    STALE,
}

enum class OperationsSupportReturnState {
    APPLIED,
    EXPIRED,
    STALE,
}

data class OpenOperationsSupportInvestigationCommand(
    val supportActionRequestId: UUID,
    val supportActionRevisionId: UUID,
    val revisionNumber: Int,
    val requesterActorId: UUID,
    val supportApproverActorId: UUID?,
    val executorActorId: UUID,
    val expiresAt: Instant,
    val openedAt: Instant,
)

data class OperationsSupportInvestigationSnapshot(
    val investigationId: UUID,
    val supportActionRequestId: UUID,
    val supportActionRevisionId: UUID,
    val revisionNumber: Int,
    val state: OperationsSupportInvestigationState,
    val openedAt: Instant,
    val expiresAt: Instant,
    val decidedByActorId: UUID?,
    val decidedAt: Instant?,
    val version: Long,
)

data class ReturnOperationsSupportInvestigationCommand(
    val supportActionRequestId: UUID,
    val supportActionRevisionId: UUID,
    val revisionNumber: Int,
    val actorId: UUID?,
    val decision: OperationsSupportInvestigationDecision?,
    val terminalState: OperationsSupportReturnState,
    val occurredAt: Instant,
)

data class OperationsSupportReturnResult(
    val state: OperationsSupportReturnState,
    val supportRequestState: String,
    val supportRequestVersion: Long,
)

interface OperationsSupportInvestigationOperations {
    /** Opens or exactly replays one investigation for one immutable Support revision. */
    fun open(command: OpenOperationsSupportInvestigationCommand): OperationsSupportInvestigationSnapshot
}

interface OperationsSupportInvestigationReturnHandler {
    /** Required synchronous owner callback; failure must roll back the Operations decision. */
    fun returnDecision(command: ReturnOperationsSupportInvestigationCommand): OperationsSupportReturnResult
}
