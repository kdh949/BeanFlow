package io.github.kdh949.beanflow.support.internal.domain

import java.time.Instant
import java.util.UUID

internal enum class SupportActionApprovalRoute {
    NONE,
    SUPPORT_MANAGER,
    OPERATIONS,
    SUPPORT_MANAGER_THEN_OPERATIONS,
}

internal enum class SupportActionRequestState {
    AWAITING_SUPPORT_MANAGER,
    AWAITING_OPERATIONS,
    READY_FOR_EXECUTION,
    REASSIGNMENT_REQUIRED,
    REVISION_REQUIRED,
    DENIED,
    EXPIRED,
    STALE,
    MANUAL_REVIEW,
    EXECUTED,
    RESOLUTION_REQUIRED,
}

internal enum class SupportApprovalStepType {
    SUPPORT_MANAGER,
    OPERATIONS,
}

internal enum class SupportApprovalStepState {
    PENDING,
    APPROVED,
    DENIED,
    RETURNED,
    EXPIRED,
    STALE,
    ESCALATED,
}

internal enum class SupportApprovalDecision {
    APPROVE,
    DENY,
    RETURN_FOR_REVISION,
}

internal enum class OperationsInvestigationDecision {
    APPROVE,
    DENY,
    RETURN_FOR_REVISION,
    ESCALATE,
}

internal data class SupportActionRevision(
    val id: UUID,
    val revisionNumber: Int,
    val action: SupportActionType,
    val targetId: UUID,
    val actionPayloadDigest: String,
    val verificationSessionId: UUID,
    val policyVersion: String,
    val targetVersion: Long,
    val amountKrw: Long?,
    val reason: String,
    val evidenceDigest: String,
    val expiresAt: Instant,
    val createdByActorId: UUID,
    val createdAt: Instant,
) {
    init {
        require(revisionNumber > 0) { "Action revision number must be positive" }
        require(actionPayloadDigest.matches(SHA_256)) { "Action payload digest must be lowercase SHA-256" }
        require(evidenceDigest.matches(SHA_256)) { "Evidence digest must be lowercase SHA-256" }
        require(policyVersion.length in 1..160 && policyVersion.none(Char::isISOControl)) { "Policy version is invalid" }
        require(targetVersion >= 0) { "Target version cannot be negative" }
        require(amountKrw == null || amountKrw >= 0) { "Action amount cannot be negative" }
        require(reason.trim().length in 1..500 && reason.none(Char::isISOControl)) { "Action reason is invalid" }
        require(createdAt.isBefore(expiresAt)) { "Action revision must expire after creation" }
    }

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

internal data class SupportApprovalChange(
    val revisionNumber: Int,
    val stepType: SupportApprovalStepType,
    val stepState: SupportApprovalStepState,
    val previousState: SupportActionRequestState,
    val currentState: SupportActionRequestState,
    val actorId: UUID?,
    val occurredAt: Instant,
    val requestVersion: Long,
)

internal data class SupportActionRevisionChange(
    val previousRevisionNumber: Int,
    val currentRevisionNumber: Int,
    val staleStepTypes: Set<SupportApprovalStepType>,
    val currentState: SupportActionRequestState,
    val requestVersion: Long,
)

internal data class SupportActionReassignmentChange(
    val previousExecutorActorId: UUID,
    val currentExecutorActorId: UUID,
    val previousState: SupportActionRequestState,
    val currentState: SupportActionRequestState,
    val requestVersion: Long,
    val occurredAt: Instant,
)

internal data class SupportActionExecutionChange(
    val executionId: UUID,
    val previousState: SupportActionRequestState,
    val currentState: SupportActionRequestState,
    val requestVersion: Long,
    val occurredAt: Instant,
    val replayed: Boolean,
)

internal class SupportActionRequest private constructor(
    val id: UUID,
    val supportCaseId: UUID,
    val requesterActorId: UUID,
    val route: SupportActionApprovalRoute,
    var executorActorId: UUID,
    var currentRevision: SupportActionRevision,
    var state: SupportActionRequestState,
    var supportApproverActorId: UUID?,
    var operationsApproverActorId: UUID?,
    var terminalExecutionId: UUID?,
    var terminalResolutionId: UUID?,
    var version: Long,
    private var lastChangedAt: Instant,
) {
    fun decideSupportManager(
        actorId: UUID,
        revisionNumber: Int,
        decision: SupportApprovalDecision,
        occurredAt: Instant,
    ): SupportApprovalChange {
        check(state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER) { "Action request is not awaiting Support Manager" }
        requireCurrentRevision(revisionNumber)
        requireFresh(occurredAt)
        requireReviewerSeparated(actorId)
        require(
            route == SupportActionApprovalRoute.SUPPORT_MANAGER || route == SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS,
        ) {
            "Action request does not have a Support Manager step"
        }
        require(occurredAt >= lastChangedAt) { "Approval time cannot move backward" }

        val previous = state
        supportApproverActorId = actorId
        state =
            when (decision) {
                SupportApprovalDecision.APPROVE -> {
                    if (route == SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS) {
                        SupportActionRequestState.AWAITING_OPERATIONS
                    } else {
                        SupportActionRequestState.READY_FOR_EXECUTION
                    }
                }

                SupportApprovalDecision.DENY -> {
                    SupportActionRequestState.DENIED
                }

                SupportApprovalDecision.RETURN_FOR_REVISION -> {
                    SupportActionRequestState.REVISION_REQUIRED
                }
            }
        return approvalChange(
            SupportApprovalStepType.SUPPORT_MANAGER,
            decision.toStepState(),
            previous,
            actorId,
            occurredAt,
        )
    }

    fun decideOperations(
        actorId: UUID,
        revisionNumber: Int,
        decision: OperationsInvestigationDecision,
        occurredAt: Instant,
    ): SupportApprovalChange {
        check(state == SupportActionRequestState.AWAITING_OPERATIONS) { "Action request is not awaiting Operations" }
        requireCurrentRevision(revisionNumber)
        requireFresh(occurredAt)
        requireReviewerSeparated(actorId)
        require(actorId != supportApproverActorId) { "One actor cannot perform both approval steps" }
        require(route == SupportActionApprovalRoute.OPERATIONS || route == SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS) {
            "Action request does not have an Operations step"
        }
        require(occurredAt >= lastChangedAt) { "Investigation decision time cannot move backward" }

        val previous = state
        operationsApproverActorId = actorId
        state =
            when (decision) {
                OperationsInvestigationDecision.APPROVE -> SupportActionRequestState.READY_FOR_EXECUTION
                OperationsInvestigationDecision.DENY -> SupportActionRequestState.DENIED
                OperationsInvestigationDecision.RETURN_FOR_REVISION -> SupportActionRequestState.REVISION_REQUIRED
                OperationsInvestigationDecision.ESCALATE -> SupportActionRequestState.MANUAL_REVIEW
            }
        return approvalChange(
            SupportApprovalStepType.OPERATIONS,
            decision.toStepState(),
            previous,
            actorId,
            occurredAt,
        )
    }

    fun revise(
        revision: SupportActionRevision,
        actorId: UUID,
        occurredAt: Instant,
    ): SupportActionRevisionChange {
        require(actorId == requesterActorId) { "Only the requester can create a new action revision" }
        check(
            state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER ||
                state == SupportActionRequestState.AWAITING_OPERATIONS ||
                state == SupportActionRequestState.REVISION_REQUIRED,
        ) { "Action request state does not allow a new revision" }
        require(revision.revisionNumber == currentRevision.revisionNumber + 1) { "Action revision sequence is invalid" }
        require(revision.action == currentRevision.action) { "Action type cannot change within a request" }
        require(revision.targetId == currentRevision.targetId) { "Action target cannot change within a request" }
        require(revision.createdByActorId == actorId) { "Action revision creator is invalid" }
        require(occurredAt >= lastChangedAt) { "Action revision time cannot move backward" }

        val previousRevision = currentRevision.revisionNumber
        val staleSteps = route.stepTypes()
        currentRevision = revision
        supportApproverActorId = null
        operationsApproverActorId = null
        state = route.initialState()
        version += 1
        lastChangedAt = occurredAt
        return SupportActionRevisionChange(previousRevision, revision.revisionNumber, staleSteps, state, version)
    }

    fun expire(occurredAt: Instant): SupportApprovalChange {
        check(state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER || state == SupportActionRequestState.AWAITING_OPERATIONS) {
            "Only a pending approval can expire"
        }
        require(!occurredAt.isBefore(currentRevision.expiresAt)) { "Action revision has not expired" }
        require(occurredAt >= lastChangedAt) { "Action expiry time cannot move backward" }
        val previous = state
        val stepType =
            if (state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER) {
                SupportApprovalStepType.SUPPORT_MANAGER
            } else {
                SupportApprovalStepType.OPERATIONS
            }
        state = SupportActionRequestState.EXPIRED
        return approvalChange(stepType, SupportApprovalStepState.EXPIRED, previous, null, occurredAt)
    }

    fun markStale(occurredAt: Instant): SupportApprovalChange {
        check(state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER || state == SupportActionRequestState.AWAITING_OPERATIONS) {
            "Only a pending approval can become stale"
        }
        require(occurredAt >= lastChangedAt) { "Action stale time cannot move backward" }
        val previous = state
        val stepType =
            if (state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER) {
                SupportApprovalStepType.SUPPORT_MANAGER
            } else {
                SupportApprovalStepType.OPERATIONS
            }
        state = SupportActionRequestState.STALE
        return approvalChange(stepType, SupportApprovalStepState.STALE, previous, null, occurredAt)
    }

    fun requireReassignment(occurredAt: Instant) {
        check(state == SupportActionRequestState.READY_FOR_EXECUTION) { "Only ready work can require reassignment" }
        require(occurredAt >= lastChangedAt) { "Reassignment state time cannot move backward" }
        state = SupportActionRequestState.REASSIGNMENT_REQUIRED
        version += 1
        lastChangedAt = occurredAt
    }

    fun reassignExecutor(
        targetActorId: UUID,
        occurredAt: Instant,
    ): SupportActionReassignmentChange {
        check(state == SupportActionRequestState.READY_FOR_EXECUTION || state == SupportActionRequestState.REASSIGNMENT_REQUIRED) {
            "Action request is not eligible for reassignment"
        }
        require(targetActorId != supportApproverActorId && targetActorId != operationsApproverActorId) {
            "An approver cannot execute the approved action"
        }
        require(targetActorId != executorActorId) { "Action executor is already assigned" }
        require(occurredAt >= lastChangedAt) { "Reassignment time cannot move backward" }

        val previousActor = executorActorId
        val previousState = state
        executorActorId = targetActorId
        state = SupportActionRequestState.READY_FOR_EXECUTION
        version += 1
        lastChangedAt = occurredAt
        return SupportActionReassignmentChange(previousActor, targetActorId, previousState, state, version, occurredAt)
    }

    fun completeExecution(
        executionId: UUID,
        actorId: UUID,
        revisionNumber: Int,
        payloadDigest: String,
        targetVersion: Long,
        occurredAt: Instant,
    ): SupportActionExecutionChange =
        finishExecution(
            executionId,
            actorId,
            revisionNumber,
            payloadDigest,
            targetVersion,
            occurredAt,
            SupportActionRequestState.EXECUTED,
            resolution = false,
        )

    fun completeResolutionExecution(
        resolutionId: UUID,
        actorId: UUID,
        revisionNumber: Int,
        payloadDigest: String,
        targetVersion: Long,
        occurredAt: Instant,
    ): SupportActionExecutionChange {
        require(currentRevision.action == SupportActionType.POST_ACCEPTANCE_RESOLUTION) {
            "Only a post-acceptance request can be consumed by a ResolutionCase"
        }
        return finishExecution(
            resolutionId,
            actorId,
            revisionNumber,
            payloadDigest,
            targetVersion,
            occurredAt,
            SupportActionRequestState.EXECUTED,
            resolution = true,
        )
    }

    fun requirePostAcceptanceResolution(
        executionId: UUID,
        actorId: UUID,
        revisionNumber: Int,
        payloadDigest: String,
        targetVersion: Long,
        occurredAt: Instant,
    ): SupportActionExecutionChange =
        finishExecution(
            executionId,
            actorId,
            revisionNumber,
            payloadDigest,
            targetVersion,
            occurredAt,
            SupportActionRequestState.RESOLUTION_REQUIRED,
            resolution = false,
        )

    private fun finishExecution(
        executionId: UUID,
        actorId: UUID,
        revisionNumber: Int,
        payloadDigest: String,
        targetVersion: Long,
        occurredAt: Instant,
        terminalState: SupportActionRequestState,
        resolution: Boolean,
    ): SupportActionExecutionChange {
        require(actorId == executorActorId) { "Only the assigned actor can execute the action" }
        check(revisionNumber == currentRevision.revisionNumber) { "Action execution revision is stale" }
        check(payloadDigest == currentRevision.actionPayloadDigest) { "Action execution payload is stale" }
        check(targetVersion == currentRevision.targetVersion) { "Action execution target version is stale" }
        val terminalId = if (resolution) terminalResolutionId else terminalExecutionId
        if (state == terminalState && terminalId == executionId) {
            return SupportActionExecutionChange(executionId, state, state, version, occurredAt, true)
        }
        check(state == SupportActionRequestState.READY_FOR_EXECUTION) { "Action request is not ready for execution" }
        check(occurredAt.isBefore(currentRevision.expiresAt)) { "Action revision has expired" }
        require(occurredAt >= lastChangedAt) { "Action execution time cannot move backward" }
        val previous = state
        state = terminalState
        if (resolution) {
            check(terminalExecutionId == null) { "Action request already has a direct terminal execution" }
            terminalResolutionId = executionId
        } else {
            check(terminalResolutionId == null) { "Action request already has a terminal ResolutionCase" }
            terminalExecutionId = executionId
        }
        version += 1
        lastChangedAt = occurredAt
        return SupportActionExecutionChange(executionId, previous, state, version, occurredAt, false)
    }

    private fun approvalChange(
        stepType: SupportApprovalStepType,
        stepState: SupportApprovalStepState,
        previousState: SupportActionRequestState,
        actorId: UUID?,
        occurredAt: Instant,
    ): SupportApprovalChange {
        version += 1
        lastChangedAt = occurredAt
        return SupportApprovalChange(
            currentRevision.revisionNumber,
            stepType,
            stepState,
            previousState,
            state,
            actorId,
            occurredAt,
            version,
        )
    }

    private fun requireCurrentRevision(revisionNumber: Int) {
        check(revisionNumber == currentRevision.revisionNumber) { "Action approval revision is stale" }
    }

    private fun requireFresh(now: Instant) {
        check(now.isBefore(currentRevision.expiresAt)) { "Action revision has expired" }
    }

    private fun requireReviewerSeparated(actorId: UUID) {
        require(actorId != requesterActorId) { "Requester cannot review the action" }
        require(actorId != executorActorId) { "Execution candidate cannot review the action" }
    }

    companion object {
        fun open(
            id: UUID,
            supportCaseId: UUID,
            requesterActorId: UUID,
            executorActorId: UUID,
            route: SupportActionApprovalRoute,
            revision: SupportActionRevision,
        ): SupportActionRequest {
            require(revision.revisionNumber == 1) { "Initial action revision must be one" }
            require(revision.createdByActorId == requesterActorId) { "Initial action revision creator is invalid" }
            return SupportActionRequest(
                id,
                supportCaseId,
                requesterActorId,
                route,
                executorActorId,
                revision,
                route.initialState(),
                null,
                null,
                null,
                null,
                0,
                revision.createdAt,
            )
        }

        fun reconstitute(
            id: UUID,
            supportCaseId: UUID,
            requesterActorId: UUID,
            executorActorId: UUID,
            route: SupportActionApprovalRoute,
            revision: SupportActionRevision,
            state: SupportActionRequestState,
            supportApproverActorId: UUID?,
            operationsApproverActorId: UUID?,
            version: Long,
            lastChangedAt: Instant,
            terminalExecutionId: UUID? = null,
            terminalResolutionId: UUID? = null,
        ): SupportActionRequest {
            require(version >= 0) { "Action request version is invalid" }
            require(lastChangedAt >= revision.createdAt) { "Action request change time is invalid" }
            require(supportApproverActorId == null || supportApproverActorId != requesterActorId) { "Requester cannot be Support approver" }
            require(
                operationsApproverActorId == null || operationsApproverActorId != requesterActorId,
            ) { "Requester cannot be Operations approver" }
            require(
                supportApproverActorId == null || operationsApproverActorId == null ||
                    supportApproverActorId != operationsApproverActorId,
            ) { "Approval actors must differ" }
            require(executorActorId != supportApproverActorId && executorActorId != operationsApproverActorId) {
                "Approver cannot be action executor"
            }
            require(terminalExecutionId == null || terminalResolutionId == null) {
                "Action request cannot have two terminal results"
            }
            require(
                (state == SupportActionRequestState.EXECUTED || state == SupportActionRequestState.RESOLUTION_REQUIRED) ==
                    (terminalExecutionId != null || terminalResolutionId != null),
            ) { "Action request terminal state binding is invalid" }
            require(terminalResolutionId == null || state == SupportActionRequestState.EXECUTED) {
                "ResolutionCase can only bind an executed action request"
            }
            return SupportActionRequest(
                id,
                supportCaseId,
                requesterActorId,
                route,
                executorActorId,
                revision,
                state,
                supportApproverActorId,
                operationsApproverActorId,
                terminalExecutionId,
                terminalResolutionId,
                version,
                lastChangedAt,
            )
        }
    }
}

private fun SupportActionApprovalRoute.initialState(): SupportActionRequestState =
    when (this) {
        SupportActionApprovalRoute.NONE -> SupportActionRequestState.READY_FOR_EXECUTION

        SupportActionApprovalRoute.SUPPORT_MANAGER,
        SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS,
        -> SupportActionRequestState.AWAITING_SUPPORT_MANAGER

        SupportActionApprovalRoute.OPERATIONS -> SupportActionRequestState.AWAITING_OPERATIONS
    }

private fun SupportActionApprovalRoute.stepTypes(): Set<SupportApprovalStepType> =
    when (this) {
        SupportActionApprovalRoute.NONE -> emptySet()
        SupportActionApprovalRoute.SUPPORT_MANAGER -> setOf(SupportApprovalStepType.SUPPORT_MANAGER)
        SupportActionApprovalRoute.OPERATIONS -> setOf(SupportApprovalStepType.OPERATIONS)
        SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS -> SupportApprovalStepType.entries.toSet()
    }

private fun SupportApprovalDecision.toStepState(): SupportApprovalStepState =
    when (this) {
        SupportApprovalDecision.APPROVE -> SupportApprovalStepState.APPROVED
        SupportApprovalDecision.DENY -> SupportApprovalStepState.DENIED
        SupportApprovalDecision.RETURN_FOR_REVISION -> SupportApprovalStepState.RETURNED
    }

private fun OperationsInvestigationDecision.toStepState(): SupportApprovalStepState =
    when (this) {
        OperationsInvestigationDecision.APPROVE -> SupportApprovalStepState.APPROVED
        OperationsInvestigationDecision.DENY -> SupportApprovalStepState.DENIED
        OperationsInvestigationDecision.RETURN_FOR_REVISION -> SupportApprovalStepState.RETURNED
        OperationsInvestigationDecision.ESCALATE -> SupportApprovalStepState.ESCALATED
    }
