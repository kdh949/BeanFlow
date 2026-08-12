package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OpenOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationReturnHandler
import io.github.kdh949.beanflow.operations.api.OperationsSupportReturnResult
import io.github.kdh949.beanflow.operations.api.OperationsSupportReturnState
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.ReturnOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRoute
import io.github.kdh949.beanflow.support.internal.domain.SupportActionDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequest
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRevision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalChange
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalStepState
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalStepType
import io.github.kdh949.beanflow.support.internal.domain.SupportCase
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationDecision as PublicOperationsDecision
import io.github.kdh949.beanflow.support.internal.domain.OperationsInvestigationDecision as DomainOperationsDecision

internal data class CreateSupportActionRequestCommand(
    val actorId: UUID,
    val caseId: UUID,
    val action: SupportActionType,
    val orderId: UUID,
    val expectedTargetVersion: Long,
    val verificationSessionId: UUID,
    val actionPayloadDigest: String,
    val amountKrw: Long?,
    val reason: String,
    val evidenceDigest: String,
    val idempotencyKey: String,
)

internal data class ReviseSupportActionRequestCommand(
    val actorId: UUID,
    val requestId: UUID,
    val expectedRevisionNumber: Int,
    val expectedRequestVersion: Long,
    val expectedTargetVersion: Long,
    val verificationSessionId: UUID,
    val actionPayloadDigest: String,
    val amountKrw: Long?,
    val reason: String,
    val evidenceDigest: String,
    val idempotencyKey: String,
)

internal data class DecideSupportManagerApprovalCommand(
    val actorId: UUID,
    val requestId: UUID,
    val revisionNumber: Int,
    val expectedRequestVersion: Long,
    val decision: SupportApprovalDecision,
    val reason: String,
    val idempotencyKey: String,
)

internal data class ReassignSupportActionRequestCommand(
    val actorId: UUID,
    val requestId: UUID,
    val revisionNumber: Int,
    val expectedRequestVersion: Long,
    val expectedCaseVersion: Long,
    val assigneeId: UUID,
    val reason: String,
    val idempotencyKey: String,
)

internal data class SupportApprovalStepResource(
    val stepType: SupportApprovalStepType,
    val state: SupportApprovalStepState,
    val decidedByActorId: UUID?,
    val decidedAt: Instant?,
)

internal data class SupportActionRequestResource(
    val requestId: UUID,
    val caseId: UUID,
    val action: SupportActionType,
    val targetId: UUID,
    val requesterActorId: UUID,
    val executorActorId: UUID,
    val revisionNumber: Int,
    val state: SupportActionRequestState,
    val approvalRoute: SupportActionApprovalRoute,
    val actionPayloadDigest: String,
    val verificationSessionId: UUID,
    val policyVersion: String,
    val targetVersion: Long,
    val amountKrw: Long?,
    val evidenceDigest: String,
    val expiresAt: Instant,
    val requestVersion: Long,
    val approvalSteps: List<SupportApprovalStepResource>,
    val terminalExecutionId: UUID?,
    val terminalResolutionId: UUID?,
    val terminalCompensationId: UUID? = null,
    val terminalProfileChangeId: UUID? = null,
)

internal sealed interface SupportActionCommandOutcome {
    data class Succeeded(
        val resource: SupportActionRequestResource,
    ) : SupportActionCommandOutcome

    data class Failed(
        val resource: SupportActionRequestResource,
        val code: FailureCode,
        val message: String,
    ) : SupportActionCommandOutcome
}

internal data class SupportActionRequestGuard(
    val requestId: UUID,
    val caseId: UUID,
    val action: SupportActionType,
    val targetId: UUID,
    val revisionNumber: Int,
    val requestVersion: Long,
)

@Service
internal class SupportActionRequestApplicationService(
    private val evaluations: SupportActionEvaluationApplicationService,
    private val ordering: OrderingSupportTimelineOperations,
    private val profileVersions: SupportProfileChangeTargetVersionOperations,
    private val investigations: OperationsSupportInvestigationOperations,
    private val transactions: SupportActionRequestTransactionService,
) {
    fun create(command: CreateSupportActionRequestCommand): SupportActionRequestResource {
        val evaluation =
            evaluations.evaluate(
                EvaluateSupportActionCommand(
                    command.actorId,
                    command.caseId,
                    command.action,
                    command.orderId,
                    command.expectedTargetVersion,
                    command.verificationSessionId,
                ),
            )
        if (evaluation.decision == SupportActionDecision.DENIED) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Current action policy denies this request")
        }
        return transactions.create(command, evaluation)
    }

    fun revise(command: ReviseSupportActionRequestCommand): SupportActionRequestResource {
        val guard = transactions.requesterGuard(command.actorId, command.requestId)
        val evaluation =
            evaluations.evaluate(
                EvaluateSupportActionCommand(
                    command.actorId,
                    guard.caseId,
                    guard.action,
                    guard.targetId,
                    command.expectedTargetVersion,
                    command.verificationSessionId,
                ),
            )
        if (evaluation.decision == SupportActionDecision.DENIED) {
            throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Current action policy denies the new revision")
        }
        return transactions.revise(command, evaluation)
    }

    @Transactional
    fun decideSupportManager(command: DecideSupportManagerApprovalCommand): SupportActionCommandOutcome {
        val guard = transactions.managerGuard(command.actorId, command.requestId)
        val currentTargetVersion =
            when (guard.action) {
                SupportActionType.GOODWILL_COMPENSATION -> transactions.compensationTargetVersion(guard.targetId)
                SupportActionType.PROFILE_CHANGE -> profileVersions.currentVersion(guard.targetId)
                else -> ordering.findOrderSnapshots(setOf(guard.targetId)).singleOrNull()?.version ?: notFound("Order")
            }
        val outcome = transactions.decideSupportManager(command, currentTargetVersion)
        if (outcome is SupportActionCommandOutcome.Succeeded &&
            outcome.resource.action == SupportActionType.PROFILE_CHANGE &&
            outcome.resource.state == SupportActionRequestState.AWAITING_OPERATIONS
        ) {
            val binding = transactions.profileInvestigationBinding(outcome.resource.requestId)
            investigations.open(
                OpenOperationsSupportInvestigationCommand(
                    binding.requestId,
                    binding.revisionId,
                    binding.revisionNumber,
                    binding.requesterActorId,
                    binding.supportApproverActorId,
                    binding.executorActorId,
                    binding.expiresAt,
                    binding.occurredAt,
                ),
            )
        }
        return outcome
    }

    fun get(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestResource = transactions.get(actorId, requestId)

    fun reassign(command: ReassignSupportActionRequestCommand): SupportActionRequestResource = transactions.reassign(command)

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")
}

@Service
internal class SupportActionRequestTransactionService(
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val steps: SupportActionApprovalStepJpaRepository,
    private val idempotencies: SupportActionCommandIdempotencyJpaRepository,
    private val reassignments: SupportActionReassignmentJpaRepository,
    private val cases: SupportCaseJpaRepository,
    private val caseAssignments: SupportCaseAssignmentHistoryJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val compensationRequests: SupportCompensationRequestJpaRepository,
    private val ordering: OrderingSupportTimelineOperations,
    private val profileVersions: SupportProfileChangeTargetVersionOperations,
    private val reassignmentProjection: SupportActionReassignmentProjectionUpdater,
    private val permissions: OperatorPermissionAuthorization,
    private val commandLock: SupportCaseCommandLock,
    private val canonicalizer: SupportCommandPayloadCanonicalizer,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : OperationsSupportInvestigationReturnHandler {
    @Transactional(propagation = Propagation.MANDATORY)
    fun openProfileChangeApproval(command: OpenProfileChangeApprovalCommand): SupportActionRequestResource {
        val revision =
            SupportActionRevision(
                command.revisionId,
                1,
                SupportActionType.PROFILE_CHANGE,
                command.profileChangeId,
                command.payloadDigest,
                command.verificationSessionId,
                PROFILE_CHANGE_POLICY_VERSION,
                command.expectedProfileVersion,
                null,
                command.reason,
                command.evidenceDigest,
                command.expiresAt,
                command.actorId,
                command.occurredAt,
            )
        val aggregate =
            SupportActionRequest.open(
                command.requestId,
                command.caseId,
                command.actorId,
                command.actorId,
                SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS,
                revision,
            )
        val entity = aggregate.toEntity(command.occurredAt)
        requests.saveAndFlush(entity)
        val revisionEntity = revision.toEntity(entity.id)
        revisions.saveAndFlush(revisionEntity)
        return resource(entity, revisionEntity, emptyList())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun reviseProfileChangeApproval(command: ReviseProfileChangeApprovalCommand): SupportActionRequestResource {
        val entity = requests.findLockedById(command.actionRequestId) ?: notFound("SupportActionRequest")
        if (entity.action != SupportActionType.PROFILE_CHANGE || entity.targetId != command.profileChangeId ||
            entity.requesterActorId != command.actorId || entity.version != command.expectedRequestVersion
        ) {
            stale()
        }
        val current = currentRevision(entity)
        val aggregate = entity.toAggregate(current)
        val next =
            SupportActionRevision(
                identifiers.next(),
                current.revisionNumber + 1,
                SupportActionType.PROFILE_CHANGE,
                command.profileChangeId,
                command.payloadDigest,
                command.verificationSessionId,
                PROFILE_CHANGE_POLICY_VERSION,
                command.expectedProfileVersion,
                null,
                command.reason,
                command.evidenceDigest,
                command.expiresAt,
                command.actorId,
                command.occurredAt,
            )
        val change =
            try {
                aggregate.revise(next, command.actorId, command.occurredAt)
            } catch (_: IllegalArgumentException) {
                stale()
            } catch (_: IllegalStateException) {
                conflict("Profile change approval does not allow revision")
            }
        change.staleStepTypes.forEach { type ->
            if (!steps.existsByRequestIdAndRevisionNumberAndStepType(entity.id, current.revisionNumber, type)) {
                steps.saveAndFlush(
                    stepEntity(
                        entity.id,
                        current,
                        type,
                        SupportApprovalStepState.STALE,
                        null,
                        "PROFILE_PAYLOAD_REVISED",
                        command.occurredAt,
                    ),
                )
            }
        }
        entity.apply(aggregate, command.occurredAt)
        requests.saveAndFlush(entity)
        val nextEntity = next.toEntity(entity.id)
        revisions.saveAndFlush(nextEntity)
        appendAudits(
            listOf(
                audit(
                    entity,
                    command.actorId,
                    "SUPPORT_ACTION_REVISION_CREATED",
                    "PROFILE_PAYLOAD_REVISED",
                    null,
                    entity.state,
                    command.occurredAt,
                ),
            ),
        )
        return resource(entity, nextEntity, emptyList())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun completeProfileChangeApproval(command: ProfileChangeExecutionApprovalCommand): SupportActionRequestResource {
        val entity = requests.findLockedById(command.actionRequestId) ?: notFound("SupportActionRequest")
        if (entity.action != SupportActionType.PROFILE_CHANGE || entity.targetId != command.profileChangeId ||
            entity.version != command.expectedRequestVersion
        ) {
            stale()
        }
        val revision = currentRevision(entity)
        val aggregate = entity.toAggregate(revision)
        val change =
            try {
                aggregate.completeProfileChangeExecution(
                    command.profileChangeId,
                    command.actorId,
                    command.revisionNumber,
                    command.payloadDigest,
                    command.expectedProfileVersion,
                    command.occurredAt,
                )
            } catch (_: IllegalArgumentException) {
                denied()
            } catch (_: IllegalStateException) {
                stale()
            }
        entity.apply(aggregate, command.occurredAt)
        requests.saveAndFlush(entity)
        if (!change.replayed) {
            appendAudits(
                listOf(
                    audit(
                        entity,
                        command.actorId,
                        "SUPPORT_ACTION_REQUEST_EXECUTED",
                        "PROFILE_CHANGE_EXECUTED",
                        change.previousState,
                        change.currentState,
                        command.occurredAt,
                    ),
                ),
            )
        }
        return resource(entity, revision, currentSteps(entity))
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun profileInvestigationBinding(requestId: UUID): ProfileChangeInvestigationBinding {
        val entity = requests.findById(requestId).orElse(null) ?: notFound("SupportActionRequest")
        if (entity.action != SupportActionType.PROFILE_CHANGE || entity.state != SupportActionRequestState.AWAITING_OPERATIONS) {
            conflict("Profile change is not awaiting Operations")
        }
        val revision = currentRevision(entity)
        return ProfileChangeInvestigationBinding(
            entity.id,
            revision.id,
            revision.revisionNumber,
            entity.requesterActorId,
            entity.supportApproverActorId ?: dependency("Support approver binding is missing"),
            entity.executorActorId,
            revision.expiresAt,
            clock.instant(),
        )
    }

    @Transactional
    fun create(
        command: CreateSupportActionRequestCommand,
        evaluation: SupportActionEvaluationResource,
    ): SupportActionRequestResource {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        permissions.requireActive(normalized.actorId, normalized.action.capabilityPermission())
        commandLock.lock(normalized.caseId, normalized.actorId, CREATE.name, normalized.idempotencyKey)
        val payloadHash = normalized.createPayloadHash()
        replay(normalized.actorId, CREATE, normalized.idempotencyKey, payloadHash)?.let { return it.resourceOrThrow() }
        val supportCase = cases.findLockedById(normalized.caseId) ?: notFound("SupportCase")
        requireRequesterScope(supportCase, normalized.actorId, normalized.orderId)
        val session = requireActionSession(normalized.verificationSessionId, normalized.actorId, normalized.caseId)
        val now = clock.instant()
        if (!now.isBefore(session.expiresAt)) expired("VerificationSession")
        if (!now.isBefore(evaluation.expiresAt) || evaluation.policyVersion != SupportActionPolicy.POLICY_VERSION ||
            evaluation.targetVersion != normalized.expectedTargetVersion
        ) {
            stale()
        }
        val route = evaluation.toApprovalRoute()
        val requestId = identifiers.next()
        val revision =
            SupportActionRevision(
                id = identifiers.next(),
                revisionNumber = 1,
                action = normalized.action,
                targetId = normalized.orderId,
                actionPayloadDigest = normalized.actionPayloadDigest,
                verificationSessionId = normalized.verificationSessionId,
                policyVersion = evaluation.policyVersion,
                targetVersion = evaluation.targetVersion,
                amountKrw = normalized.amountKrw,
                reason = normalized.reason,
                evidenceDigest = normalized.evidenceDigest,
                expiresAt = session.expiresAt,
                createdByActorId = normalized.actorId,
                createdAt = now,
            )
        val aggregate =
            SupportActionRequest.open(
                requestId,
                normalized.caseId,
                normalized.actorId,
                normalized.actorId,
                route,
                revision,
            )
        val entity = aggregate.toEntity(now)
        requests.saveAndFlush(entity)
        revisions.saveAndFlush(revision.toEntity(requestId))
        appendAudits(
            listOf(
                audit(entity, normalized.actorId, "SUPPORT_ACTION_REQUEST_CREATED", "REQUEST_CREATED", null, entity.state, now),
                audit(entity, normalized.actorId, "SUPPORT_ACTION_REVISION_CREATED", "REVISION_CREATED", null, entity.state, now),
            ),
        )
        val response = resource(entity, revision.toEntity(requestId), emptyList())
        saveIdempotency(normalized.actorId, CREATE, normalized.idempotencyKey, payloadHash, response, 201, null, now)
        return response
    }

    @Transactional
    fun requesterGuard(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestGuard {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        val entity = requests.findLockedById(requestId) ?: notFound("SupportActionRequest")
        if (entity.requesterActorId != actorId) denied()
        return entity.guard()
    }

    @Transactional
    fun managerGuard(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestGuard {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val entity = requests.findLockedById(requestId) ?: notFound("SupportActionRequest")
        when (entity.action) {
            SupportActionType.GOODWILL_COMPENSATION -> {
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_APPROVE)
            }

            SupportActionType.PROFILE_CHANGE -> {
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE)
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)
            }

            else -> {
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
                permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)
            }
        }
        if (entity.requesterActorId == actorId || entity.executorActorId == actorId) {
            throw DomainFailure(FailureCode.SUPPORT_APPROVER_MUST_DIFFER, "Support approver must differ from requester and executor")
        }
        return entity.guard()
    }

    @Transactional(readOnly = true)
    fun compensationTargetVersion(compensationId: UUID): Long =
        compensationRequests.findById(compensationId).orElse(null)?.version ?: notFound("SupportCompensationRequest")

    @Transactional
    fun revise(
        command: ReviseSupportActionRequestCommand,
        evaluation: SupportActionEvaluationResource,
    ): SupportActionRequestResource {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_ACTION_REQUEST)
        commandLock.lock(null, normalized.actorId, REVISE.name, normalized.idempotencyKey)
        val payloadHash = normalized.revisePayloadHash()
        replay(normalized.actorId, REVISE, normalized.idempotencyKey, payloadHash)?.let { return it.resourceOrThrow() }
        val entity = requests.findLockedById(normalized.requestId) ?: notFound("SupportActionRequest")
        if (entity.requesterActorId != normalized.actorId) denied()
        if (entity.currentRevisionNumber != normalized.expectedRevisionNumber ||
            entity.version != normalized.expectedRequestVersion
        ) {
            stale()
        }
        permissions.requireActive(normalized.actorId, entity.action.capabilityPermission())
        val supportCase = cases.findLockedById(entity.supportCaseId) ?: notFound("SupportCase")
        requireRequesterScope(supportCase, normalized.actorId, entity.targetId)
        val session = requireActionSession(normalized.verificationSessionId, normalized.actorId, entity.supportCaseId)
        val now = clock.instant()
        if (!now.isBefore(session.expiresAt)) expired("VerificationSession")
        if (!now.isBefore(evaluation.expiresAt) || evaluation.policyVersion != SupportActionPolicy.POLICY_VERSION ||
            evaluation.targetVersion != normalized.expectedTargetVersion || evaluation.toApprovalRoute() != entity.approvalRoute
        ) {
            stale()
        }
        val currentRevision = currentRevision(entity)
        val aggregate = entity.toAggregate(currentRevision)
        val next =
            SupportActionRevision(
                identifiers.next(),
                currentRevision.revisionNumber + 1,
                entity.action,
                entity.targetId,
                normalized.actionPayloadDigest,
                normalized.verificationSessionId,
                evaluation.policyVersion,
                evaluation.targetVersion,
                normalized.amountKrw,
                normalized.reason,
                normalized.evidenceDigest,
                session.expiresAt,
                normalized.actorId,
                now,
            )
        val change = aggregate.revise(next, normalized.actorId, now)
        change.staleStepTypes.forEach { type ->
            if (!steps.existsByRequestIdAndRevisionNumberAndStepType(entity.id, currentRevision.revisionNumber, type)) {
                steps.saveAndFlush(
                    stepEntity(
                        entity.id,
                        currentRevision,
                        type,
                        SupportApprovalStepState.STALE,
                        null,
                        "REVISION_REPLACED",
                        now,
                    ),
                )
            }
        }
        entity.apply(aggregate, now)
        requests.saveAndFlush(entity)
        val revisionEntity = next.toEntity(entity.id)
        revisions.saveAndFlush(revisionEntity)
        appendAudits(
            listOf(
                audit(entity, normalized.actorId, "SUPPORT_ACTION_REVISION_CREATED", "REVISION_CREATED", null, entity.state, now),
            ),
        )
        val response = resource(entity, revisionEntity, emptyList())
        saveIdempotency(normalized.actorId, REVISE, normalized.idempotencyKey, payloadHash, response, 200, null, now)
        return response
    }

    @Transactional
    fun decideSupportManager(
        command: DecideSupportManagerApprovalCommand,
        currentTargetVersion: Long,
    ): SupportActionCommandOutcome {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_READ)
        commandLock.lock(null, normalized.actorId, MANAGER.name, normalized.idempotencyKey)
        val payloadHash = normalized.managerPayloadHash()
        replay(normalized.actorId, MANAGER, normalized.idempotencyKey, payloadHash)?.let { return it }
        val entity = requests.findLockedById(normalized.requestId) ?: notFound("SupportActionRequest")
        when (entity.action) {
            SupportActionType.GOODWILL_COMPENSATION -> {
                permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_COMPENSATION_APPROVE)
            }

            SupportActionType.PROFILE_CHANGE -> {
                permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE)
                permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)
            }

            else -> {
                permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_ORDER_READ)
                permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_ACTION_APPROVE)
            }
        }
        if (entity.currentRevisionNumber != normalized.revisionNumber || entity.version != normalized.expectedRequestVersion) stale()
        val revision = currentRevision(entity)
        val aggregate = entity.toAggregate(revision)
        val now = clock.instant()
        val terminal = approvalInvalidity(entity, revision, aggregate, currentTargetVersion, now)
        if (terminal != null) {
            val (change, code, message) = terminal
            persistApprovalChange(entity, aggregate, revision, change, "SYSTEM_POLICY_RECHECK", now)
            val response = resource(entity, revision, currentSteps(entity))
            saveIdempotency(normalized.actorId, MANAGER, normalized.idempotencyKey, payloadHash, response, 409, code, now)
            return SupportActionCommandOutcome.Failed(response, code, message)
        }
        val change =
            try {
                aggregate.decideSupportManager(normalized.actorId, normalized.revisionNumber, normalized.decision, now)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(
                    FailureCode.SUPPORT_APPROVER_MUST_DIFFER,
                    "Support approver must differ from requester, executor and other reviewers",
                )
            } catch (_: IllegalStateException) {
                conflict("Support action request is not awaiting this approval")
            }
        persistApprovalChange(entity, aggregate, revision, change, normalized.reason, now)
        val response = resource(entity, revision, currentSteps(entity))
        saveIdempotency(normalized.actorId, MANAGER, normalized.idempotencyKey, payloadHash, response, 200, null, now)
        return SupportActionCommandOutcome.Succeeded(response)
    }

    @Transactional
    fun get(
        actorId: UUID,
        requestId: UUID,
    ): SupportActionRequestResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val entity = requests.findLockedById(requestId) ?: notFound("SupportActionRequest")
        val visible =
            actorId == entity.requesterActorId || actorId == entity.executorActorId ||
                permissions.hasActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE) ||
                permissions.hasActive(actorId, OperatorPermission.SUPPORT_COMPENSATION_APPROVE) ||
                permissions.hasActive(actorId, OperatorPermission.SUPPORT_PROFILE_R3_APPROVE) ||
                permissions.hasActive(actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        if (!visible) denied()
        val revision = currentRevision(entity)
        val aggregate = entity.toAggregate(revision)
        val now = clock.instant()
        if ((
                entity.state == SupportActionRequestState.AWAITING_SUPPORT_MANAGER ||
                    entity.state == SupportActionRequestState.AWAITING_OPERATIONS
            ) &&
            !now.isBefore(revision.expiresAt)
        ) {
            val change = aggregate.expire(now)
            persistApprovalChange(entity, aggregate, revision, change, "REVISION_EXPIRED", now)
        } else if (entity.state == SupportActionRequestState.READY_FOR_EXECUTION &&
            !hasExecutionPermission(entity)
        ) {
            aggregate.requireReassignment(now)
            entity.apply(aggregate, now)
            requests.saveAndFlush(entity)
            appendAudits(
                listOf(audit(entity, "SYSTEM", "SUPPORT_ACTION_APPROVAL_STALE", "EXECUTOR_PERMISSION_REVOKED", null, entity.state, now)),
            )
        }
        return resource(entity, revision, currentSteps(entity))
    }

    @Transactional
    fun reassign(command: ReassignSupportActionRequestCommand): SupportActionRequestResource {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_ASSIGN)
        val observed = requests.findById(normalized.requestId).orElseThrow { notFound("SupportActionRequest") }
        commandLock.lock(observed.supportCaseId, normalized.actorId, REASSIGN.name, normalized.idempotencyKey)
        val payloadHash = normalized.reassignPayloadHash()
        replay(normalized.actorId, REASSIGN, normalized.idempotencyKey, payloadHash)?.let { return it.resourceOrThrow() }

        val entity = requests.findLockedById(normalized.requestId) ?: notFound("SupportActionRequest")
        if (entity.supportCaseId != observed.supportCaseId ||
            entity.currentRevisionNumber != normalized.revisionNumber ||
            entity.version != normalized.expectedRequestVersion
        ) {
            stale()
        }
        if (normalized.assigneeId == entity.supportApproverActorId || normalized.assigneeId == entity.operationsApproverActorId) {
            throw DomainFailure(
                FailureCode.SUPPORT_APPROVER_MUST_DIFFER,
                "An approver cannot execute the approved action",
            )
        }
        if (normalized.assigneeId == entity.executorActorId) {
            conflict("Support action executor is already assigned")
        }
        permissions.requireActive(normalized.assigneeId, OperatorPermission.SUPPORT_CASE_WRITE)
        if (entity.action == SupportActionType.GOODWILL_COMPENSATION) {
            permissions.requireActive(normalized.assigneeId, OperatorPermission.SUPPORT_COMPENSATION_EXECUTE)
        } else {
            permissions.requireActive(normalized.assigneeId, OperatorPermission.SUPPORT_ACTION_EXECUTE)
            permissions.requireActive(normalized.assigneeId, entity.action.executionCapabilityPermission())
        }

        val supportCase = cases.findLockedById(entity.supportCaseId) ?: notFound("SupportCase")
        if (supportCase.version != normalized.expectedCaseVersion || supportCase.state !in ACTIVE_CASE_STATES) stale()
        val revision = currentRevision(entity)
        val requestAggregate = entity.toAggregate(revision)
        val caseAggregate = supportCase.toActionAggregate()
        val now = clock.instant()
        val requestChange =
            try {
                requestAggregate.reassignExecutor(normalized.assigneeId, now)
            } catch (_: IllegalArgumentException) {
                conflict("Support action reassignment binding is invalid")
            } catch (_: IllegalStateException) {
                conflict("Support action request is not eligible for reassignment")
            }
        val caseChange =
            try {
                caseAggregate.assign(normalized.assigneeId, normalized.actorId, now)
            } catch (_: IllegalStateException) {
                conflict("Support case is not eligible for reassignment")
            }

        entity.apply(requestAggregate, now)
        reassignmentProjection.update(entity.id, entity.action, normalized.assigneeId, now)
        supportCase.applyActionAggregate(caseAggregate)
        requests.saveAndFlush(entity)
        cases.saveAndFlush(supportCase)
        reassignments.saveAndFlush(
            SupportActionReassignmentEntity(
                identifiers.next(),
                entity.id,
                revision.revisionNumber,
                requestChange.previousExecutorActorId,
                requestChange.currentExecutorActorId,
                normalized.actorId,
                normalized.reason,
                caseChange.caseVersion,
                requestChange.requestVersion,
                now,
            ),
        )
        caseAssignments.saveAndFlush(
            SupportCaseAssignmentHistoryEntity(
                identifiers.next(),
                supportCase.id,
                caseAssignments.nextSequence(supportCase.id),
                caseChange.previousAssigneeId,
                caseChange.currentAssigneeId,
                normalized.actorId,
                caseChange.caseVersion,
                now,
            ),
        )
        appendAudits(
            listOf(
                caseAssignmentAudit(supportCase, normalized.actorId, now),
                audit(
                    entity,
                    normalized.actorId,
                    "SUPPORT_ACTION_REQUEST_REASSIGNED",
                    "EXECUTOR_REASSIGNED",
                    requestChange.previousState,
                    requestChange.currentState,
                    now,
                ),
            ),
        )
        val response = resource(entity, revision, currentSteps(entity))
        saveIdempotency(normalized.actorId, REASSIGN, normalized.idempotencyKey, payloadHash, response, 200, null, now)
        return response
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun returnDecision(command: ReturnOperationsSupportInvestigationCommand): OperationsSupportReturnResult {
        val entity = requests.findLockedById(command.supportActionRequestId) ?: notFound("SupportActionRequest")
        if (entity.currentRevisionNumber != command.revisionNumber) stale()
        val revision = currentRevision(entity)
        if (revision.id != command.supportActionRevisionId) stale()
        val aggregate = entity.toAggregate(revision)

        if (command.terminalState == OperationsSupportReturnState.EXPIRED) {
            val change =
                try {
                    aggregate.expire(command.occurredAt)
                } catch (_: IllegalStateException) {
                    conflict("Support action request is not awaiting Operations")
                }
            persistApprovalChange(entity, aggregate, revision, change, "INVESTIGATION_EXPIRED", command.occurredAt)
            return OperationsSupportReturnResult(
                OperationsSupportReturnState.EXPIRED,
                entity.state.name,
                entity.version,
            )
        }

        val currentTargetVersion =
            when (entity.action) {
                SupportActionType.GOODWILL_COMPENSATION -> {
                    compensationRequests.findById(entity.targetId).orElse(null)?.version ?: notFound("SupportCompensationRequest")
                }

                SupportActionType.PROFILE_CHANGE -> {
                    profileVersions.currentVersion(entity.targetId)
                }

                else -> {
                    ordering.findOrderSnapshots(setOf(entity.targetId)).singleOrNull()?.version ?: notFound("Order")
                }
            }
        val invalidity = approvalInvalidity(entity, revision, aggregate, currentTargetVersion, command.occurredAt)
        if (invalidity != null) {
            val (change, code) = invalidity
            persistApprovalChange(entity, aggregate, revision, change, "OPERATIONS_POLICY_RECHECK", command.occurredAt)
            val state =
                if (code == FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED) {
                    OperationsSupportReturnState.EXPIRED
                } else {
                    OperationsSupportReturnState.STALE
                }
            return OperationsSupportReturnResult(state, entity.state.name, entity.version)
        }

        val actorId = command.actorId ?: invalid("Operations reviewer is required")
        val decision = command.decision ?: invalid("Operations decision is required")
        val change =
            try {
                aggregate.decideOperations(actorId, command.revisionNumber, decision.toDomain(), command.occurredAt)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(
                    FailureCode.SUPPORT_APPROVER_MUST_DIFFER,
                    "Operations reviewer must differ from requester, executor and Support approver",
                )
            } catch (_: IllegalStateException) {
                conflict("Support action request is not awaiting Operations")
            }
        persistApprovalChange(entity, aggregate, revision, change, "OPERATIONS_DECISION_APPLIED", command.occurredAt)
        return OperationsSupportReturnResult(OperationsSupportReturnState.APPLIED, entity.state.name, entity.version)
    }

    private fun approvalInvalidity(
        entity: SupportActionRequestEntity,
        revision: SupportActionRevisionEntity,
        aggregate: SupportActionRequest,
        currentTargetVersion: Long,
        now: Instant,
    ): Triple<SupportApprovalChange, FailureCode, String>? {
        if (!now.isBefore(revision.expiresAt)) {
            return Triple(
                aggregate.expire(now),
                FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED,
                "Support action request approval has expired",
            )
        }
        val session = sessions.findLockedById(revision.verificationSessionId)
        val validSession =
            session != null && session.actorId == entity.requesterActorId && session.supportCaseId == entity.supportCaseId &&
                session.state == VerificationState.VERIFIED && session.actionScope == VerificationActionScope.SUPPORT_ACTION &&
                session.purpose == VerificationPurpose.CASE_RESOLUTION && now.isBefore(session.expiresAt) &&
                session.expiresAt == revision.expiresAt
        val validRequester =
            when (entity.action) {
                SupportActionType.GOODWILL_COMPENSATION -> {
                    permissions.hasActive(entity.requesterActorId, OperatorPermission.SUPPORT_COMPENSATION_REQUEST)
                }

                else -> {
                    permissions.hasActive(entity.requesterActorId, OperatorPermission.SUPPORT_ACTION_REQUEST) &&
                        permissions.hasActive(entity.requesterActorId, entity.action.capabilityPermission())
                }
            }
        val validPolicyVersion =
            when (entity.action) {
                SupportActionType.GOODWILL_COMPENSATION -> {
                    compensationRequests
                        .findById(entity.targetId)
                        .orElse(null)
                        ?.policyVersionId
                        ?.toString() == revision.policyVersion
                }

                SupportActionType.PROFILE_CHANGE -> {
                    revision.policyVersion == PROFILE_CHANGE_POLICY_VERSION
                }

                else -> {
                    revision.policyVersion == SupportActionPolicy.POLICY_VERSION
                }
            }
        if (!validSession || !validRequester || !validPolicyVersion ||
            revision.targetVersion != currentTargetVersion
        ) {
            return Triple(
                aggregate.markStale(now),
                FailureCode.SUPPORT_ACTION_REQUEST_STALE,
                "Support action request approval binding is stale",
            )
        }
        return null
    }

    private fun hasExecutionPermission(entity: SupportActionRequestEntity): Boolean =
        if (entity.action == SupportActionType.GOODWILL_COMPENSATION) {
            permissions.hasActive(entity.executorActorId, OperatorPermission.SUPPORT_COMPENSATION_EXECUTE)
        } else {
            permissions.hasActive(entity.executorActorId, OperatorPermission.SUPPORT_ACTION_EXECUTE) &&
                permissions.hasActive(entity.executorActorId, entity.action.executionCapabilityPermission())
        }

    private fun persistApprovalChange(
        entity: SupportActionRequestEntity,
        aggregate: SupportActionRequest,
        revision: SupportActionRevisionEntity,
        change: SupportApprovalChange,
        reason: String,
        now: Instant,
    ) {
        if (steps.existsByRequestIdAndRevisionNumberAndStepType(entity.id, revision.revisionNumber, change.stepType)) {
            conflict("Support approval step is already terminal")
        }
        steps.saveAndFlush(
            stepEntity(
                entity.id,
                revision,
                change.stepType,
                change.stepState,
                change.actorId,
                reason,
                now,
            ),
        )
        entity.apply(aggregate, now)
        requests.saveAndFlush(entity)
        val action =
            when (change.stepState) {
                SupportApprovalStepState.EXPIRED -> {
                    "SUPPORT_ACTION_APPROVAL_EXPIRED"
                }

                SupportApprovalStepState.STALE -> {
                    "SUPPORT_ACTION_APPROVAL_STALE"
                }

                else -> {
                    if (change.stepType == SupportApprovalStepType.OPERATIONS) {
                        "SUPPORT_ACTION_OPERATIONS_DECIDED"
                    } else {
                        "SUPPORT_ACTION_SUPPORT_MANAGER_DECIDED"
                    }
                }
            }
        appendAudits(
            listOf(
                audit(
                    entity,
                    change.actorId?.toString() ?: "SYSTEM",
                    action,
                    change.stepState.name,
                    change.previousState,
                    change.currentState,
                    now,
                ),
            ),
        )
    }

    private fun requireActionSession(
        sessionId: UUID,
        actorId: UUID,
        caseId: UUID,
    ): VerificationSessionEntity {
        val session = sessions.findLockedById(sessionId) ?: notFound("VerificationSession")
        if (session.actorId != actorId || session.supportCaseId != caseId || session.state != VerificationState.VERIFIED ||
            session.actionScope != VerificationActionScope.SUPPORT_ACTION || session.purpose != VerificationPurpose.CASE_RESOLUTION
        ) {
            throw DomainFailure(FailureCode.VERIFICATION_REQUIRED, "Current action-bound verification is required")
        }
        val subjectLink = subjectLinks.findByIdAndSupportCaseId(session.subjectLinkId, caseId)
        if (subjectLink == null || subjectLink.unlinkedAt != null || subjectLink.subjectId != session.subjectId) {
            denied()
        }
        return session
    }

    private fun requireRequesterScope(
        supportCase: SupportCaseEntity,
        actorId: UUID,
        orderId: UUID,
    ) {
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val linked =
            subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                supportCase.id,
                SupportSubjectType.ORDER,
                orderId,
                SupportSubjectRelationship.RELATED_ORDER,
            )
        if (!linked) denied()
    }

    private fun currentRevision(entity: SupportActionRequestEntity): SupportActionRevisionEntity =
        revisions.findByRequestIdAndRevisionNumber(entity.id, entity.currentRevisionNumber) ?: dependency("Action revision is missing")

    private fun currentSteps(entity: SupportActionRequestEntity): List<SupportActionApprovalStepEntity> =
        steps.findByRequestIdAndRevisionNumberOrderByStepTypeAsc(entity.id, entity.currentRevisionNumber)

    private fun resource(
        entity: SupportActionRequestEntity,
        revision: SupportActionRevisionEntity,
        approvalSteps: List<SupportActionApprovalStepEntity>,
    ) = SupportActionRequestResource(
        entity.id,
        entity.supportCaseId,
        entity.action,
        entity.targetId,
        entity.requesterActorId,
        entity.executorActorId,
        revision.revisionNumber,
        entity.state,
        entity.approvalRoute,
        revision.actionPayloadDigest,
        revision.verificationSessionId,
        revision.policyVersion,
        revision.targetVersion,
        revision.amountKrw,
        revision.evidenceDigest,
        revision.expiresAt,
        entity.version,
        approvalSteps.map { SupportApprovalStepResource(it.stepType, it.state, it.decidedByActorId, it.decidedAt) },
        entity.terminalExecutionId,
        entity.terminalResolutionId,
        entity.terminalCompensationId,
        entity.terminalProfileChangeId,
    )

    private fun replay(
        actorId: UUID,
        operation: SupportActionCommandOperation,
        key: String,
        hash: String,
    ): SupportActionCommandOutcome? {
        val existing = idempotencies.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key) ?: return null
        if (existing.payloadHash != hash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another support action command")
        }
        val response = objectMapper.readValue(existing.responseBody, SupportActionRequestResource::class.java)
        val failure = existing.failureCode?.let(FailureCode::valueOf)
        return if (failure == null) {
            SupportActionCommandOutcome.Succeeded(response)
        } else {
            SupportActionCommandOutcome.Failed(response, failure, failureMessage(failure))
        }
    }

    private fun saveIdempotency(
        actorId: UUID,
        operation: SupportActionCommandOperation,
        key: String,
        hash: String,
        response: SupportActionRequestResource,
        status: Int,
        failure: FailureCode?,
        now: Instant,
    ) {
        idempotencies.saveAndFlush(
            SupportActionCommandIdempotencyEntity(
                identifiers.next(),
                actorId,
                operation,
                key,
                hash,
                response.requestId,
                status,
                objectMapper.writeValueAsString(response),
                failure?.name,
                now,
                now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
    }

    private fun SupportActionCommandOutcome.resourceOrThrow(): SupportActionRequestResource =
        when (this) {
            is SupportActionCommandOutcome.Succeeded -> resource
            is SupportActionCommandOutcome.Failed -> throw DomainFailure(code, message)
        }

    private fun appendAudits(commands: List<AppendAuditRecordCommand>) {
        audits.appendAll(commands)
    }

    private fun audit(
        entity: SupportActionRequestEntity,
        actorId: UUID,
        action: String,
        event: String,
        before: SupportActionRequestState?,
        after: SupportActionRequestState,
        now: Instant,
    ): AppendAuditRecordCommand = audit(entity, actorId.toString(), action, event, before, after, now)

    private fun audit(
        entity: SupportActionRequestEntity,
        actorId: String,
        action: String,
        event: String,
        before: SupportActionRequestState?,
        after: SupportActionRequestState,
        now: Instant,
    ) = AppendAuditRecordCommand(
        actorId = actorId,
        actorType = if (actorId == "SYSTEM") AuditActorType.SYSTEM else AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.OPERATIONS_POLICY,
        action = action,
        targetType = "SUPPORT_ACTION_REQUEST",
        targetId = entity.id,
        occurredAt = now,
        reason = "SUPPORT_ACTION_APPROVAL",
        beforeSummary = before?.let { mapOf("state" to it.name) } ?: emptyMap(),
        afterSummary =
            mapOf(
                "event" to event,
                "state" to after.name,
                "revision" to entity.currentRevisionNumber.toString(),
            ),
        correlationId = correlations.currentOrCreate(),
        sourceReference = "support-action:${entity.id}:$action:${entity.version}",
    )

    private fun caseAssignmentAudit(
        entity: SupportCaseEntity,
        actorId: UUID,
        now: Instant,
    ) = AppendAuditRecordCommand(
        actorId = actorId.toString(),
        actorType = AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.OPERATIONS_POLICY,
        action = "SUPPORT_CASE_ASSIGNED",
        targetType = "SUPPORT_CASE",
        targetId = entity.id,
        occurredAt = now,
        reason = "SUPPORT_ACTION_REASSIGNMENT",
        afterSummary =
            mapOf(
                "event" to "SUPPORT_CASE_ASSIGNED",
                "state" to entity.state.name,
                "caseVersion" to entity.version.toString(),
            ),
        correlationId = correlations.currentOrCreate(),
        sourceReference = "support-case:${entity.id}:SUPPORT_CASE_ASSIGNED:${entity.version}",
    )

    private fun stepEntity(
        requestId: UUID,
        revision: SupportActionRevisionEntity,
        stepType: SupportApprovalStepType,
        state: SupportApprovalStepState,
        actorId: UUID?,
        reason: String,
        now: Instant,
    ) = SupportActionApprovalStepEntity(
        identifiers.next(),
        requestId,
        revision.id,
        revision.revisionNumber,
        stepType,
        state,
        actorId,
        reason,
        now,
        now,
    )

    private fun SupportActionRequest.toEntity(now: Instant) =
        SupportActionRequestEntity(
            id,
            supportCaseId,
            currentRevision.action,
            currentRevision.action.targetType(),
            currentRevision.targetId,
            requesterActorId,
            executorActorId,
            currentRevision.revisionNumber,
            route,
            state,
            supportApproverActorId,
            operationsApproverActorId,
            terminalExecutionId,
            terminalResolutionId,
            now,
            now,
            version,
            terminalCompensationId,
            terminalProfileChangeId,
        )

    private fun SupportActionRevision.toEntity(requestId: UUID) =
        SupportActionRevisionEntity(
            id,
            requestId,
            revisionNumber,
            action,
            action.targetType(),
            targetId,
            actionPayloadDigest,
            verificationSessionId,
            policyVersion,
            targetVersion,
            amountKrw,
            reason,
            evidenceDigest,
            expiresAt,
            createdByActorId,
            createdAt,
        )

    private fun SupportActionType.targetType(): SupportActionTargetType =
        when (this) {
            SupportActionType.GOODWILL_COMPENSATION -> SupportActionTargetType.COMPENSATION_REQUEST
            SupportActionType.PROFILE_CHANGE -> SupportActionTargetType.PROFILE_CHANGE_REQUEST
            else -> SupportActionTargetType.ORDER
        }

    private fun SupportActionRequestEntity.guard() =
        SupportActionRequestGuard(id, supportCaseId, action, targetId, currentRevisionNumber, version)

    private fun SupportActionEvaluationResource.toApprovalRoute(): SupportActionApprovalRoute =
        when (decision) {
            SupportActionDecision.ALLOWED -> SupportActionApprovalRoute.NONE
            SupportActionDecision.APPROVAL_REQUIRED -> SupportActionApprovalRoute.SUPPORT_MANAGER
            SupportActionDecision.DENIED -> throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Action policy denied")
        }

    private fun CreateSupportActionRequestCommand.normalized() =
        copy(
            idempotencyKey = idempotencyKey.normalizedKey(),
            actionPayloadDigest = actionPayloadDigest.normalizedDigest("Action payload"),
            evidenceDigest = evidenceDigest.normalizedDigest("Evidence"),
            reason = reason.normalizedReason(),
        ).also {
            if (it.expectedTargetVersion < 0 || (it.amountKrw != null && it.amountKrw < 0)) invalid("Action numeric binding is invalid")
        }

    private fun ReviseSupportActionRequestCommand.normalized() =
        copy(
            idempotencyKey = idempotencyKey.normalizedKey(),
            actionPayloadDigest = actionPayloadDigest.normalizedDigest("Action payload"),
            evidenceDigest = evidenceDigest.normalizedDigest("Evidence"),
            reason = reason.normalizedReason(),
        ).also {
            if (it.expectedRevisionNumber < 1 || it.expectedRequestVersion < 0 || it.expectedTargetVersion < 0 ||
                (it.amountKrw != null && it.amountKrw < 0)
            ) {
                invalid("Action revision binding is invalid")
            }
        }

    private fun DecideSupportManagerApprovalCommand.normalized() =
        copy(idempotencyKey = idempotencyKey.normalizedKey(), reason = reason.normalizedReason()).also {
            if (it.revisionNumber < 1 || it.expectedRequestVersion < 0) invalid("Approval binding is invalid")
        }

    private fun ReassignSupportActionRequestCommand.normalized() =
        copy(idempotencyKey = idempotencyKey.normalizedKey(), reason = reason.normalizedReason()).also {
            if (it.revisionNumber < 1 || it.expectedRequestVersion < 0 || it.expectedCaseVersion < 0) {
                invalid("Action reassignment binding is invalid")
            }
        }

    private fun CreateSupportActionRequestCommand.createPayloadHash(): String =
        hash(
            canonicalizer.canonical(
                CREATE.name,
                listOf(
                    field("actorId", "uuid", actorId),
                    field("caseId", "uuid", caseId),
                    field("action", "enum", action),
                    field("orderId", "uuid", orderId),
                    field("expectedTargetVersion", "int64", expectedTargetVersion),
                    field("verificationSessionId", "uuid", verificationSessionId),
                    field("actionPayloadDigest", "sha256", actionPayloadDigest),
                    field("amountKrw", "int64", amountKrw),
                    field("reason", "string", reason),
                    field("evidenceDigest", "sha256", evidenceDigest),
                ),
            ),
        )

    private fun ReviseSupportActionRequestCommand.revisePayloadHash(): String =
        hash(
            canonicalizer.canonical(
                REVISE.name,
                listOf(
                    field("actorId", "uuid", actorId),
                    field("requestId", "uuid", requestId),
                    field("expectedRevisionNumber", "int32", expectedRevisionNumber),
                    field("expectedRequestVersion", "int64", expectedRequestVersion),
                    field("expectedTargetVersion", "int64", expectedTargetVersion),
                    field("verificationSessionId", "uuid", verificationSessionId),
                    field("actionPayloadDigest", "sha256", actionPayloadDigest),
                    field("amountKrw", "int64", amountKrw),
                    field("reason", "string", reason),
                    field("evidenceDigest", "sha256", evidenceDigest),
                ),
            ),
        )

    private fun DecideSupportManagerApprovalCommand.managerPayloadHash(): String =
        hash(
            canonicalizer.canonical(
                MANAGER.name,
                listOf(
                    field("actorId", "uuid", actorId),
                    field("requestId", "uuid", requestId),
                    field("revisionNumber", "int32", revisionNumber),
                    field("expectedRequestVersion", "int64", expectedRequestVersion),
                    field("decision", "enum", decision),
                    field("reason", "string", reason),
                ),
            ),
        )

    private fun ReassignSupportActionRequestCommand.reassignPayloadHash(): String =
        hash(
            canonicalizer.canonical(
                REASSIGN.name,
                listOf(
                    field("actorId", "uuid", actorId),
                    field("requestId", "uuid", requestId),
                    field("revisionNumber", "int32", revisionNumber),
                    field("expectedRequestVersion", "int64", expectedRequestVersion),
                    field("expectedCaseVersion", "int64", expectedCaseVersion),
                    field("assigneeId", "uuid", assigneeId),
                    field("reason", "string", reason),
                ),
            ),
        )

    private fun SupportCaseEntity.toActionAggregate(): SupportCase =
        SupportCase.reconstitute(
            id,
            requesterType,
            requesterReference,
            category,
            priority,
            openedAt,
            currentAssigneeId,
            state,
            version,
            closedAt,
            lastChangedAt,
        )

    private fun SupportCaseEntity.applyActionAggregate(aggregate: SupportCase) {
        currentAssigneeId = aggregate.assigneeId
        state = aggregate.state
        version = aggregate.version
        closedAt = aggregate.closedAt
        lastChangedAt = aggregate.latestChangeAt
    }

    private fun field(
        name: String,
        type: String,
        value: Any?,
    ) = SupportCommandPayloadField(name, type, value?.toString())

    private fun String.normalizedKey(): String =
        trim().also { value ->
            if (value.length !in 8..128 || value.any(Char::isISOControl)) invalid("Idempotency-Key is invalid")
        }

    private fun String.normalizedDigest(label: String): String =
        trim().also { value ->
            if (!value.matches(SHA_256)) invalid("$label digest must be lowercase SHA-256")
        }

    private fun String.normalizedReason(): String =
        trim().also { value ->
            if (value.length !in 1..500 || value.any(Char::isISOControl)) invalid("Support action reason is invalid")
        }

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun failureMessage(code: FailureCode): String =
        when (code) {
            FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED -> "Support action request approval has expired"
            FailureCode.SUPPORT_ACTION_REQUEST_STALE -> "Support action request approval binding is stale"
            else -> "Support action command failed"
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Support action object scope is required")

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Support action request is stale")

    private fun expired(resource: String): Nothing =
        throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED, "$resource has expired")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STATE_CONFLICT, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        val CREATE = SupportActionCommandOperation.CREATE_REQUEST
        val REVISE = SupportActionCommandOperation.REVISE_REQUEST
        val MANAGER = SupportActionCommandOperation.MANAGER_DECISION
        val REASSIGN = SupportActionCommandOperation.REASSIGN_REQUEST
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
    }
}

private fun PublicOperationsDecision.toDomain(): DomainOperationsDecision =
    when (this) {
        PublicOperationsDecision.APPROVE -> DomainOperationsDecision.APPROVE
        PublicOperationsDecision.DENY -> DomainOperationsDecision.DENY
        PublicOperationsDecision.RETURN_FOR_REVISION -> DomainOperationsDecision.RETURN_FOR_REVISION
        PublicOperationsDecision.ESCALATE -> DomainOperationsDecision.ESCALATE
    }
