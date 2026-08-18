package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActor
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.ordering.api.OrderingSupportOrderCancellationOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportPickupRescheduleOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderCancellationCommand
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerReport
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerResult
import io.github.kdh949.beanflow.ordering.api.SupportOrderState
import io.github.kdh949.beanflow.ordering.api.SupportPickupRescheduleCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.ConsumeSupportOrderChangeAuthorizationCommand
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorization
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorizationConsumption
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorizationType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeCostResponsibility
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class CreateSupportOrderChangeAuthorizationCommand(
    val actorId: UUID,
    val actorRoles: Set<StoreActorRole>,
    val storeId: UUID,
    val authorizationType: SupportOrderChangeAuthorizationType,
    val action: SupportActionType,
    val policyVersion: String,
    val requestId: UUID?,
    val revisionNumber: Int?,
    val expectedRequestVersion: Long?,
    val costResponsibility: SupportOrderChangeCostResponsibility,
    val idempotencyKey: String,
)

internal data class SupportOrderChangeAuthorizationResource(
    val authorizationId: UUID,
    val storeId: UUID,
    val authorizationType: SupportOrderChangeAuthorizationType,
    val action: SupportActionType,
    val policyVersion: String,
    val requestId: UUID?,
    val revisionNumber: Int?,
    val actionPayloadDigest: String?,
    val targetVersion: Long?,
    val authorizedByActorId: UUID,
    val authorizedAt: Instant,
    val expiresAt: Instant,
    val maxSuccessfulUses: Int,
    val successfulUses: Int,
    val costResponsibility: SupportOrderChangeCostResponsibility,
)

internal data class ExecuteSupportOrderChangeCommand(
    val actorId: UUID,
    val requestId: UUID,
    val action: SupportActionType,
    val revisionNumber: Int,
    val expectedRequestVersion: Long,
    val expectedTargetVersion: Long,
    val cancellationReasonCode: CustomerCancellationReasonCode?,
    val newPickupSlotId: UUID?,
    val authorizationId: UUID?,
    val idempotencyKey: String,
)

internal data class SupportOrderChangeExecutionResource(
    val executionId: UUID,
    val requestId: UUID,
    val revisionNumber: Int,
    val action: SupportActionType,
    val outcome: SupportOrderChangeExecutionOutcome,
    val previousTargetState: String,
    val currentTargetState: String,
    val previousPickupSlotId: UUID,
    val currentPickupSlotId: UUID,
    val expectedTargetVersion: Long,
    val targetVersionAfter: Long,
    val paymentRecoveryState: String?,
    val authorizationId: UUID?,
    val occurredAt: Instant,
    val requestState: SupportActionRequestState,
    val requestVersion: Long,
)

@Component
internal class SupportOrderChangePayloadCanonicalizer(
    private val canonicalizer: SupportCommandPayloadCanonicalizer,
) {
    fun actionDigest(
        command: ExecuteSupportOrderChangeCommand,
        orderId: UUID,
    ): String =
        sha256(
            canonicalizer.canonical(
                ACTION_PAYLOAD,
                listOf(
                    field("action", "enum", command.action),
                    field("orderId", "uuid", orderId),
                    field("cancellationReasonCode", "enum", command.cancellationReasonCode),
                    field("newPickupSlotId", "uuid", command.newPickupSlotId),
                ),
            ),
        )

    fun executionHash(command: ExecuteSupportOrderChangeCommand): String =
        sha256(
            canonicalizer.canonical(
                EXECUTE,
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("requestId", "uuid", command.requestId),
                    field("action", "enum", command.action),
                    field("revisionNumber", "int32", command.revisionNumber),
                    field("expectedRequestVersion", "int64", command.expectedRequestVersion),
                    field("expectedTargetVersion", "int64", command.expectedTargetVersion),
                    field("cancellationReasonCode", "enum", command.cancellationReasonCode),
                    field("newPickupSlotId", "uuid", command.newPickupSlotId),
                    field("authorizationId", "uuid", command.authorizationId),
                ),
            ),
        )

    fun authorizationHash(command: CreateSupportOrderChangeAuthorizationCommand): String =
        sha256(
            canonicalizer.canonical(
                AUTHORIZE,
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("storeId", "uuid", command.storeId),
                    field("authorizationType", "enum", command.authorizationType),
                    field("action", "enum", command.action),
                    field("policyVersion", "string", command.policyVersion),
                    field("requestId", "uuid", command.requestId),
                    field("revisionNumber", "int32", command.revisionNumber),
                    field("expectedRequestVersion", "int64", command.expectedRequestVersion),
                    field("costResponsibility", "enum", command.costResponsibility),
                ),
            ),
        )

    private fun field(
        name: String,
        type: String,
        value: Any?,
    ) = SupportCommandPayloadField(name, type, value?.toString())

    private fun sha256(value: String): String = SupportSha256.utf8(value)

    private companion object {
        const val ACTION_PAYLOAD = "SUPPORT_ORDER_CHANGE_ACTION_V1"
        const val EXECUTE = "EXECUTE_SUPPORT_ORDER_CHANGE"
        const val AUTHORIZE = "CREATE_SUPPORT_ORDER_CHANGE_AUTHORIZATION"
    }
}

@Service
internal class SupportOrderChangeAuthorizationApplicationService(
    private val transactions: SupportOrderChangeAuthorizationTransactionService,
) {
    fun create(command: CreateSupportOrderChangeAuthorizationCommand): SupportOrderChangeAuthorizationResource =
        transactions.create(command)
}

@Service
internal class SupportOrderChangeAuthorizationTransactionService(
    private val storeAccess: StoreAccessOperations,
    private val authorizations: SupportOrderChangeAuthorizationJpaRepository,
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val ordering: OrderingSupportTimelineOperations,
    private val commandLock: SupportCaseCommandLock,
    private val payloads: SupportOrderChangePayloadCanonicalizer,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun create(raw: CreateSupportOrderChangeAuthorizationCommand): SupportOrderChangeAuthorizationResource {
        val command = raw.normalized()
        val storeActor = storeAccess.requireOrderManagementAccess(command.actorId, command.storeId, command.actorRoles)
        commandLock.lock(null, command.actorId, AUTHORIZE, command.idempotencyKey)
        val payloadHash = payloads.authorizationHash(command)
        authorizations.findByAuthorizedByActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)?.let {
            if (it.payloadHash != payloadHash) reused()
            return it.resource(0)
        }
        val now = now()
        val authorization =
            when (command.authorizationType) {
                SupportOrderChangeAuthorizationType.CONFIRMATION -> confirmation(command, now)
                SupportOrderChangeAuthorizationType.DELEGATION -> delegation(command, now)
            }
        val entity = authorization.toEntity(command.idempotencyKey, payloadHash)
        authorizations.saveAndFlush(entity)
        audits.appendAll(listOf(authorizationAudit(entity, storeActor)))
        return entity.resource(0)
    }

    private fun confirmation(
        command: CreateSupportOrderChangeAuthorizationCommand,
        now: Instant,
    ): SupportOrderChangeAuthorization {
        val requestId = command.requestId ?: invalid("Confirmation request binding is required")
        val revisionNumber = command.revisionNumber ?: invalid("Confirmation revision binding is required")
        val expectedRequestVersion = command.expectedRequestVersion ?: invalid("Confirmation request version is required")
        val request = requests.findLockedById(requestId) ?: notFound("SupportActionRequest")
        if (request.version != expectedRequestVersion || request.currentRevisionNumber != revisionNumber ||
            request.action != command.action
        ) {
            stale()
        }
        if (request.state !in AUTHORIZABLE_REQUEST_STATES) conflict("Support action request cannot receive store confirmation")
        if (command.actorId in
            setOfNotNull(
                request.requesterActorId,
                request.executorActorId,
                request.supportApproverActorId,
                request.operationsApproverActorId,
            )
        ) {
            scopeMismatch("Store authorizer must differ from Support request actors")
        }
        val revision = revisions.findByRequestIdAndRevisionNumber(request.id, revisionNumber) ?: dependency("Action revision is missing")
        if (!now.isBefore(revision.expiresAt) || revision.policyVersion != SupportActionPolicy.POLICY_VERSION) {
            expired("Support action request")
        }
        val order = ordering.findOrderSnapshots(setOf(request.targetId)).singleOrNull() ?: notFound("Order")
        if (order.storeId != command.storeId || order.state != SupportOrderState.ACCEPTED) {
            scopeMismatch("Store confirmation does not match an accepted order")
        }
        if (order.version != revision.targetVersion) stale()
        return SupportOrderChangeAuthorization.confirmation(
            identifiers.next(),
            command.storeId,
            command.action,
            request.id,
            revision.revisionNumber,
            revision.actionPayloadDigest,
            revision.targetVersion,
            revision.expiresAt,
            command.actorId,
            now,
            command.costResponsibility,
        )
    }

    private fun delegation(
        command: CreateSupportOrderChangeAuthorizationCommand,
        now: Instant,
    ): SupportOrderChangeAuthorization {
        if (command.requestId != null || command.revisionNumber != null || command.expectedRequestVersion != null) {
            invalid("Delegation cannot include request-specific binding")
        }
        return SupportOrderChangeAuthorization.delegation(
            identifiers.next(),
            command.storeId,
            command.action,
            command.policyVersion,
            command.actorId,
            now,
            command.costResponsibility,
        )
    }

    private fun authorizationAudit(
        authorization: SupportOrderChangeAuthorizationEntity,
        actor: StoreActor,
    ) = AppendAuditRecordCommand(
        actorId = actor.actorId.toString(),
        actorType = if (actor.role == StoreActorRole.OWNER) AuditActorType.STORE_OWNER else AuditActorType.STORE_STAFF,
        category = AuditCategory.ORDER_AND_FULFILLMENT,
        action =
            if (authorization.type == SupportOrderChangeAuthorizationType.CONFIRMATION) {
                "SUPPORT_ORDER_CHANGE_CONFIRMATION_CREATED"
            } else {
                "SUPPORT_ORDER_CHANGE_DELEGATION_CREATED"
            },
        targetType = "SUPPORT_ORDER_CHANGE_AUTHORIZATION",
        targetId = authorization.id,
        occurredAt = authorization.authorizedAt,
        reason = "STORE_COST_RESPONSIBILITY_ACCEPTED",
        afterSummary =
            mapOf(
                "action" to authorization.action.name,
                "authorizationType" to authorization.type.name,
                "policyVersion" to authorization.policyVersion,
                "maxSuccessfulUses" to authorization.maxSuccessfulUses.toString(),
                "costResponsibility" to authorization.costResponsibility.name,
            ),
        correlationId = correlations.currentOrCreate(),
        sourceReference = "support-order-change-authorization:${authorization.id}:created",
    )

    private fun SupportOrderChangeAuthorization.toEntity(
        idempotencyKey: String,
        payloadHash: String,
    ) = SupportOrderChangeAuthorizationEntity(
        id,
        storeId,
        action,
        type,
        policyVersion,
        requestId,
        revisionNumber,
        payloadDigest,
        targetVersion,
        authorizedByActorId,
        idempotencyKey,
        payloadHash,
        authorizedAt,
        expiresAt,
        maxSuccessfulUses,
        costResponsibility,
        revokedAt,
    )

    private fun SupportOrderChangeAuthorizationEntity.resource(successfulUses: Int) =
        SupportOrderChangeAuthorizationResource(
            id,
            storeId,
            type,
            action,
            policyVersion,
            requestId,
            revisionNumber,
            actionPayloadDigest,
            targetVersion,
            authorizedByActorId,
            authorizedAt,
            expiresAt,
            maxSuccessfulUses,
            successfulUses,
            costResponsibility,
        )

    private fun CreateSupportOrderChangeAuthorizationCommand.normalized(): CreateSupportOrderChangeAuthorizationCommand =
        copy(idempotencyKey = idempotencyKey.normalizedKey()).also {
            if (it.action !in DIRECT_ACTIONS || it.policyVersion != SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION ||
                it.costResponsibility != SupportOrderChangeCostResponsibility.STORE || it.actorRoles.isEmpty()
            ) {
                invalid("Store order change authorization is invalid")
            }
            if ((it.revisionNumber != null && it.revisionNumber < 1) ||
                (it.expectedRequestVersion != null && it.expectedRequestVersion < 0)
            ) {
                invalid("Store order change authorization binding is invalid")
            }
        }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val AUTHORIZE = "CREATE_ORDER_CHANGE_AUTHORIZATION"
        val DIRECT_ACTIONS = setOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE)
        val AUTHORIZABLE_REQUEST_STATES =
            setOf(
                SupportActionRequestState.AWAITING_SUPPORT_MANAGER,
                SupportActionRequestState.AWAITING_OPERATIONS,
                SupportActionRequestState.READY_FOR_EXECUTION,
                SupportActionRequestState.REASSIGNMENT_REQUIRED,
            )
    }
}

@Service
internal class SupportOrderChangeExecutionApplicationService(
    private val transactions: SupportOrderChangeExecutionTransactionService,
) {
    fun execute(command: ExecuteSupportOrderChangeCommand): SupportOrderChangeExecutionResource = transactions.execute(command)
}

@Service
internal class SupportOrderChangeExecutionTransactionService(
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val executions: SupportOrderChangeExecutionJpaRepository,
    private val authorizations: SupportOrderChangeAuthorizationJpaRepository,
    private val authorizationUses: SupportOrderChangeAuthorizationUseJpaRepository,
    private val ordering: OrderingSupportTimelineOperations,
    private val cancellation: OrderingSupportOrderCancellationOperations,
    private val reschedule: OrderingSupportPickupRescheduleOperations,
    private val permissions: OperatorPermissionAuthorization,
    private val commandLock: SupportCaseCommandLock,
    private val payloads: SupportOrderChangePayloadCanonicalizer,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun execute(raw: ExecuteSupportOrderChangeCommand): SupportOrderChangeExecutionResource {
        val command = raw.normalized()
        requireExecutionPermissions(command)
        commandLock.lock(null, command.actorId, EXECUTE, command.idempotencyKey)
        val payloadHash = payloads.executionHash(command)
        executions.findByActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)?.let {
            if (it.payloadHash != payloadHash) reused()
            return replay(it)
        }
        val request = requests.findLockedById(command.requestId) ?: notFound("SupportActionRequest")
        executions.findByRequestId(command.requestId)?.let { conflict("Support action request already has a terminal execution") }
        requireRequestBinding(request, command)
        val supportCase = cases.findLockedById(request.supportCaseId) ?: notFound("SupportCase")
        requireCaseScope(supportCase, request, command.actorId)
        val revision =
            revisions.findByRequestIdAndRevisionNumber(request.id, request.currentRevisionNumber)
                ?: dependency("Action revision is missing")
        val now = now()
        requireRevisionBinding(request, revision, command, now)
        val order = ordering.findOrderSnapshots(setOf(request.targetId)).singleOrNull() ?: notFound("Order")
        val executionId = identifiers.next()
        val authorization = requireAuthorizationIfAccepted(request, revision, order, command, executionId, now)
        val report = executeOwner(request, command, executionId, authorization?.id)
        if (report.result == SupportOrderChangeOwnerResult.ALREADY_APPLIED) {
            dependency("Owner order change history exists without Support execution")
        }
        val outcome =
            if (report.result == SupportOrderChangeOwnerResult.RESOLUTION_REQUIRED) {
                SupportOrderChangeExecutionOutcome.RESOLUTION_REQUIRED
            } else {
                SupportOrderChangeExecutionOutcome.EXECUTED
            }
        val aggregate = request.toAggregate(revision)
        try {
            if (outcome == SupportOrderChangeExecutionOutcome.EXECUTED) {
                aggregate.completeExecution(
                    executionId,
                    command.actorId,
                    command.revisionNumber,
                    revision.actionPayloadDigest,
                    command.expectedTargetVersion,
                    now,
                )
            } else {
                aggregate.requirePostAcceptanceResolution(
                    executionId,
                    command.actorId,
                    command.revisionNumber,
                    revision.actionPayloadDigest,
                    command.expectedTargetVersion,
                    now,
                )
            }
        } catch (_: IllegalArgumentException) {
            denied("Support action executor binding is invalid")
        } catch (_: IllegalStateException) {
            conflict("Support action request is not executable")
        }
        val execution =
            SupportOrderChangeExecutionEntity(
                executionId,
                request.id,
                revision.id,
                revision.revisionNumber,
                command.actorId,
                request.action,
                command.idempotencyKey,
                payloadHash,
                revision.actionPayloadDigest,
                command.expectedTargetVersion,
                report.orderVersion,
                report.previousState,
                report.currentState,
                report.previousPickupSlotId,
                report.currentPickupSlotId,
                report.paymentRecoveryState,
                if (outcome == SupportOrderChangeExecutionOutcome.EXECUTED) authorization?.id else null,
                outcome,
                EXECUTION_REASON,
                now,
                now.plus(EXECUTION_RETENTION),
            )
        executions.saveAndFlush(execution)
        if (outcome == SupportOrderChangeExecutionOutcome.EXECUTED && authorization != null) {
            authorizationUses.saveAndFlush(
                SupportOrderChangeAuthorizationUseEntity(
                    executionId,
                    authorization.id,
                    request.id,
                    revision.revisionNumber,
                    revision.actionPayloadDigest,
                    revision.targetVersion,
                    now,
                ),
            )
        }
        request.apply(aggregate, now)
        requests.saveAndFlush(request)
        audits.appendAll(listOf(executionAudit(execution, request)))
        return execution.resource(request)
    }

    private fun requireRequestBinding(
        request: SupportActionRequestEntity,
        command: ExecuteSupportOrderChangeCommand,
    ) {
        if (request.action != command.action || request.currentRevisionNumber != command.revisionNumber ||
            request.version != command.expectedRequestVersion
        ) {
            stale()
        }
        if (request.executorActorId != command.actorId) denied("Only the assigned Support actor can execute the action")
        if (request.state != SupportActionRequestState.READY_FOR_EXECUTION) conflict("Support action request is not ready")
        if (!permissions.hasActive(request.requesterActorId, OperatorPermission.SUPPORT_ACTION_REQUEST) ||
            !permissions.hasActive(request.requesterActorId, request.action.capabilityPermission())
        ) {
            stale()
        }
    }

    private fun requireCaseScope(
        supportCase: SupportCaseEntity,
        request: SupportActionRequestEntity,
        actorId: UUID,
    ) {
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) {
            denied("Active assigned SupportCase is required")
        }
        if (!subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                supportCase.id,
                SupportSubjectType.ORDER,
                request.targetId,
                SupportSubjectRelationship.RELATED_ORDER,
            )
        ) {
            denied("Support action target is outside the assigned case")
        }
    }

    private fun requireRevisionBinding(
        request: SupportActionRequestEntity,
        revision: SupportActionRevisionEntity,
        command: ExecuteSupportOrderChangeCommand,
        now: Instant,
    ) {
        if (!now.isBefore(revision.expiresAt)) expired("Support action request")
        if (revision.policyVersion != SupportActionPolicy.POLICY_VERSION ||
            revision.targetVersion != command.expectedTargetVersion ||
            payloads.actionDigest(command, request.targetId) != revision.actionPayloadDigest
        ) {
            stale()
        }
        val session = sessions.findLockedById(revision.verificationSessionId) ?: stale()
        if (session.actorId != request.requesterActorId || session.supportCaseId != request.supportCaseId ||
            session.state != VerificationState.VERIFIED || session.actionScope != VerificationActionScope.SUPPORT_ACTION ||
            session.purpose != VerificationPurpose.CASE_RESOLUTION || session.expiresAt != revision.expiresAt ||
            !now.isBefore(session.expiresAt)
        ) {
            stale()
        }
        val link = subjectLinks.findByIdAndSupportCaseId(session.subjectLinkId, request.supportCaseId)
        if (link == null || link.unlinkedAt != null || link.subjectId != session.subjectId) stale()
    }

    private fun requireAuthorizationIfAccepted(
        request: SupportActionRequestEntity,
        revision: SupportActionRevisionEntity,
        order: io.github.kdh949.beanflow.ordering.api.SupportOrderSnapshot,
        command: ExecuteSupportOrderChangeCommand,
        executionId: UUID,
        now: Instant,
    ): SupportOrderChangeAuthorizationEntity? {
        if (order.state !in POST_ACCEPTANCE_RESOLUTION_STATES && order.version != revision.targetVersion) stale()
        if (order.state in POST_ACCEPTANCE_RESOLUTION_STATES) return null
        if (order.state != SupportOrderState.ACCEPTED) {
            if (command.authorizationId != null) scopeMismatch("Store authorization is only valid for accepted orders")
            return null
        }
        val authorizationId = command.authorizationId ?: authorizationRequired()
        val entity = authorizations.findLockedById(authorizationId) ?: notFound("SupportOrderChangeAuthorization")
        val priorUses = authorizationUses.findByAuthorizationIdOrderByUsedAtAsc(entity.id)
        if (entity.storeId != order.storeId || entity.action != request.action || entity.revokedAt != null) {
            scopeMismatch("Store authorization scope does not match this action")
        }
        if (!now.isBefore(entity.expiresAt)) authorizationExpired()
        if (entity.authorizedByActorId in
            setOfNotNull(
                request.requesterActorId,
                request.executorActorId,
                request.supportApproverActorId,
                request.operationsApproverActorId,
            )
        ) {
            scopeMismatch("Store authorizer must differ from Support request actors")
        }
        if (entity.type == SupportOrderChangeAuthorizationType.CONFIRMATION &&
            (
                entity.requestId != request.id || entity.revisionNumber != revision.revisionNumber ||
                    entity.actionPayloadDigest != revision.actionPayloadDigest || entity.targetVersion != revision.targetVersion
            )
        ) {
            scopeMismatch("Store confirmation binding does not match the approved revision")
        }
        if (entity.type == SupportOrderChangeAuthorizationType.DELEGATION &&
            entity.policyVersion != SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION
        ) {
            scopeMismatch("Store delegation policy version is not active")
        }
        if (priorUses.size >= entity.maxSuccessfulUses) authorizationExhausted()
        val domain = entity.toDomain(priorUses)
        val consumption =
            try {
                domain.consume(
                    ConsumeSupportOrderChangeAuthorizationCommand(
                        executionId,
                        order.storeId,
                        request.action,
                        request.id,
                        revision.revisionNumber,
                        revision.actionPayloadDigest,
                        revision.targetVersion,
                    ),
                    now,
                )
            } catch (_: IllegalArgumentException) {
                scopeMismatch("Store authorization binding does not match this action")
            } catch (_: IllegalStateException) {
                authorizationExhausted()
            }
        if (consumption != SupportOrderChangeAuthorizationConsumption.APPLIED) {
            dependency("New Support execution unexpectedly replayed authorization use")
        }
        return entity
    }

    private fun executeOwner(
        request: SupportActionRequestEntity,
        command: ExecuteSupportOrderChangeCommand,
        executionId: UUID,
        authorizationId: UUID?,
    ): SupportOrderChangeOwnerReport {
        val sourceReference = "support-order-change:$executionId"
        return when (request.action) {
            SupportActionType.ORDER_CANCELLATION -> {
                cancellation.cancel(
                    SupportOrderCancellationCommand(
                        request.id,
                        executionId,
                        command.actorId,
                        request.targetId,
                        command.expectedTargetVersion,
                        command.cancellationReasonCode ?: invalid("Cancellation reason code is required"),
                        null,
                        authorizationId,
                        sourceReference,
                    ),
                )
            }

            SupportActionType.PICKUP_RESCHEDULE -> {
                reschedule.reschedule(
                    SupportPickupRescheduleCommand(
                        request.id,
                        executionId,
                        command.actorId,
                        request.targetId,
                        command.expectedTargetVersion,
                        command.newPickupSlotId ?: invalid("New pickup slot is required"),
                        authorizationId,
                        sourceReference,
                    ),
                )
            }

            SupportActionType.POST_ACCEPTANCE_RESOLUTION -> {
                invalid("Post-acceptance resolution is owned by S80")
            }

            SupportActionType.GOODWILL_COMPENSATION -> {
                invalid("Goodwill compensation is owned by S90")
            }

            SupportActionType.PROFILE_CHANGE -> {
                invalid("Profile change is owned by S100")
            }
        }
    }

    private fun replay(execution: SupportOrderChangeExecutionEntity): SupportOrderChangeExecutionResource {
        val request =
            requests.findById(execution.requestId).orElseThrow {
                DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Execution request is missing")
            }
        if (request.terminalExecutionId != execution.id) dependency("Execution terminal binding is inconsistent")
        return execution.resource(request)
    }

    private fun SupportOrderChangeExecutionEntity.resource(request: SupportActionRequestEntity) =
        SupportOrderChangeExecutionResource(
            id,
            requestId,
            revisionNumber,
            action,
            outcome,
            previousTargetState,
            currentTargetState,
            previousPickupSlotId,
            currentPickupSlotId,
            expectedTargetVersion,
            targetVersionAfter,
            paymentRecoveryState,
            authorizationId,
            occurredAt,
            request.state,
            request.version,
        )

    private fun executionAudit(
        execution: SupportOrderChangeExecutionEntity,
        request: SupportActionRequestEntity,
    ) = AppendAuditRecordCommand(
        actorId = execution.actorId.toString(),
        actorType = AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.ORDER_AND_FULFILLMENT,
        action =
            if (execution.outcome == SupportOrderChangeExecutionOutcome.EXECUTED) {
                "SUPPORT_ORDER_CHANGE_EXECUTED"
            } else {
                "SUPPORT_ORDER_CHANGE_RESOLUTION_REQUIRED"
            },
        targetType = "SUPPORT_ACTION_REQUEST",
        targetId = request.id,
        occurredAt = execution.occurredAt,
        reason = EXECUTION_REASON,
        beforeSummary = mapOf("state" to execution.previousTargetState),
        afterSummary =
            mapOf(
                "action" to execution.action.name,
                "outcome" to execution.outcome.name,
                "state" to execution.currentTargetState,
                "requestState" to request.state.name,
            ),
        correlationId = correlations.currentOrCreate(),
        sourceReference = "support-order-change-execution:${execution.id}:audit",
    )

    private fun requireExecutionPermissions(command: ExecuteSupportOrderChangeCommand) {
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_ORDER_READ)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_ACTION_EXECUTE)
        permissions.requireActive(command.actorId, command.action.capabilityPermission())
    }

    private fun ExecuteSupportOrderChangeCommand.normalized(): ExecuteSupportOrderChangeCommand =
        copy(idempotencyKey = idempotencyKey.normalizedKey()).also {
            if (it.action !in DIRECT_ACTIONS || it.revisionNumber < 1 || it.expectedRequestVersion < 0 ||
                it.expectedTargetVersion < 0
            ) {
                invalid("Support order change execution binding is invalid")
            }
            when (it.action) {
                SupportActionType.ORDER_CANCELLATION -> {
                    if (it.cancellationReasonCode == null || it.newPickupSlotId != null) {
                        invalid("Cancellation execution payload is invalid")
                    }
                }

                SupportActionType.PICKUP_RESCHEDULE -> {
                    if (it.newPickupSlotId == null || it.cancellationReasonCode != null) {
                        invalid("Pickup reschedule execution payload is invalid")
                    }
                }

                SupportActionType.POST_ACCEPTANCE_RESOLUTION -> {
                    invalid("Post-acceptance resolution is owned by S80")
                }

                SupportActionType.GOODWILL_COMPENSATION -> {
                    invalid("Goodwill compensation is owned by S90")
                }

                SupportActionType.PROFILE_CHANGE -> {
                    invalid("Profile change is owned by S100")
                }
            }
        }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val EXECUTE = "EXECUTE_ORDER_CHANGE"
        const val EXECUTION_REASON = "SUPPORT_CASE_RESOLUTION"
        val EXECUTION_RETENTION: Duration = Duration.ofDays(90)
        val DIRECT_ACTIONS = setOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE)
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val POST_ACCEPTANCE_RESOLUTION_STATES =
            setOf(SupportOrderState.PREPARING, SupportOrderState.READY, SupportOrderState.COMPLETED)
    }
}

private fun String.normalizedKey(): String =
    trim().also {
        if (it != this || it.length !in 8..128 || it.any(Char::isISOControl)) {
            invalid("Idempotency-Key is invalid")
        }
    }

private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")

private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

private fun denied(message: String): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, message)

private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Support action binding is stale")

private fun expired(resource: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED, "$resource has expired")

private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STATE_CONFLICT, message)

private fun reused(): Nothing =
    throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another order change command")

private fun authorizationRequired(): Nothing =
    throw DomainFailure(
        FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_REQUIRED,
        "Accepted order change requires store authorization",
    )

private fun authorizationExpired(): Nothing =
    throw DomainFailure(
        FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_EXPIRED,
        "Store order change authorization has expired",
    )

private fun authorizationExhausted(): Nothing =
    throw DomainFailure(
        FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_EXHAUSTED,
        "Store order change authorization use budget is exhausted",
    )

private fun scopeMismatch(message: String): Nothing =
    throw DomainFailure(FailureCode.SUPPORT_ORDER_CHANGE_AUTHORIZATION_SCOPE_MISMATCH, message)

private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
