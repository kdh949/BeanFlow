package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.loyalty.api.GoodwillPointFundingIssuer
import io.github.kdh949.beanflow.loyalty.api.GoodwillPointFundingLeg
import io.github.kdh949.beanflow.loyalty.api.GoodwillPointOperations
import io.github.kdh949.beanflow.loyalty.api.IssueGoodwillPointsCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OpenOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderFact
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponOperations
import io.github.kdh949.beanflow.promotion.api.GoodwillCouponResponsibility
import io.github.kdh949.beanflow.promotion.api.IssueGoodwillCouponCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRoute
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequest
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRevision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBand
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBenefitType
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationCostSnapshot
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationEvidenceBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationFundingIssuer
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationLimitRule
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationLimitScope
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationPolicyInput
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationPolicyResult
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationPolicyVersion
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationReasonCode
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequest
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationResponsibility
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class EvaluateSupportCompensationCommand(
    val actorId: UUID,
    val caseId: UUID,
    val incidentId: UUID,
    val orderId: UUID?,
    val expectedTargetVersion: Long,
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    val responsibility: SupportCompensationResponsibility,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    val costEvidenceDigest: String?,
    val platformShareBps: Int,
    val storeShareBps: Int,
    val verificationSessionId: UUID,
)

internal data class CreateSupportCompensationCommand(
    val actorId: UUID,
    val caseId: UUID,
    val incidentId: UUID,
    val orderId: UUID?,
    val expectedTargetVersion: Long,
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    val responsibility: SupportCompensationResponsibility,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    val costEvidenceDigest: String?,
    val platformShareBps: Int,
    val storeShareBps: Int,
    val verificationSessionId: UUID,
    val evidenceDigest: String,
    val idempotencyKey: String,
) {
    fun evaluation() =
        EvaluateSupportCompensationCommand(
            actorId,
            caseId,
            incidentId,
            orderId,
            expectedTargetVersion,
            benefitType,
            amountKrw,
            couponTemplateId,
            responsibility,
            evidenceBasis,
            costEvidenceDigest,
            platformShareBps,
            storeShareBps,
            verificationSessionId,
        )
}

internal data class ExecuteSupportCompensationCommand(
    val actorId: UUID,
    val compensationRequestId: UUID,
    val expectedRequestVersion: Long,
    val expectedTargetVersion: Long,
    val expectedPayloadDigest: String,
    val idempotencyKey: String,
)

internal data class RetrySupportCompensationNotificationCommand(
    val actorId: UUID,
    val compensationRequestId: UUID,
    val idempotencyKey: String,
)

internal data class SupportCompensationEvaluationResource(
    val policyVersionId: UUID,
    val band: SupportCompensationBand,
    val decision: SupportCompensationDecision,
    val approvalRoute: SupportActionApprovalRoute,
    val requiredVerificationLevel: VerificationLevel,
    val executable: Boolean,
    val reasonCodes: Set<SupportCompensationReasonCode>,
    val targetVersion: Long,
    val evaluatedAt: Instant,
    val expiresAt: Instant,
)

internal data class SupportCompensationResource(
    val compensationRequestId: UUID,
    val supportCaseId: UUID,
    val incidentId: UUID,
    val orderId: UUID?,
    val storeId: UUID?,
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    val policyVersionId: UUID,
    val band: SupportCompensationBand,
    val approvalRoute: SupportActionApprovalRoute,
    val actionRequestId: UUID?,
    val state: SupportCompensationRequestState,
    val payloadDigest: String,
    val terminalBenefitId: UUID?,
    val benefitIssuedAt: Instant?,
    val notificationDeliveryId: UUID?,
    val notificationState: String?,
    val notificationFailureCode: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Component
internal class SupportCompensationPayloadCanonicalizer(
    private val canonicalizer: SupportCommandPayloadCanonicalizer,
) {
    fun actionDigest(command: EvaluateSupportCompensationCommand): String =
        hash(
            canonicalizer.canonical(
                "SUPPORT_GOODWILL_COMPENSATION_ACTION_V1",
                listOf(
                    field("caseId", "uuid", command.caseId),
                    field("incidentId", "uuid", command.incidentId),
                    field("orderId", "uuid", command.orderId),
                    field("expectedTargetVersion", "int64", command.expectedTargetVersion),
                    field("benefitType", "enum", command.benefitType),
                    field("amountKrw", "int64", command.amountKrw),
                    field("couponTemplateId", "uuid", command.couponTemplateId),
                    field("responsibility", "enum", command.responsibility),
                    field("evidenceBasis", "enum", command.evidenceBasis),
                    field("costEvidenceDigest", "sha256", command.costEvidenceDigest),
                    field("platformShareBps", "int32", command.platformShareBps),
                    field("storeShareBps", "int32", command.storeShareBps),
                    field("verificationSessionId", "uuid", command.verificationSessionId),
                ),
            ),
        )

    fun createHash(command: CreateSupportCompensationCommand): String =
        hash(
            canonicalizer.canonical(
                "CREATE_SUPPORT_GOODWILL_COMPENSATION",
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("actionDigest", "sha256", actionDigest(command.evaluation())),
                    field("evidenceDigest", "sha256", command.evidenceDigest),
                ),
            ),
        )

    fun executeHash(command: ExecuteSupportCompensationCommand): String =
        hash(
            canonicalizer.canonical(
                "EXECUTE_SUPPORT_GOODWILL_COMPENSATION",
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("compensationRequestId", "uuid", command.compensationRequestId),
                    field("expectedRequestVersion", "int64", command.expectedRequestVersion),
                    field("expectedTargetVersion", "int64", command.expectedTargetVersion),
                    field("expectedPayloadDigest", "sha256", command.expectedPayloadDigest),
                ),
            ),
        )

    fun retryHash(command: RetrySupportCompensationNotificationCommand): String =
        hash(
            canonicalizer.canonical(
                "RETRY_SUPPORT_GOODWILL_NOTIFICATION",
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("compensationRequestId", "uuid", command.compensationRequestId),
                ),
            ),
        )

    private fun field(
        name: String,
        type: String,
        value: Any?,
    ) = SupportCommandPayloadField(name, type, value?.toString())

    private fun hash(value: String): String = SupportSha256.utf8(value)
}

internal data class SupportCompensationExecutionBinding(
    val orderId: UUID?,
)

private data class CompensationLimitScopeSnapshot(
    val customerId: UUID,
    val orderId: UUID?,
    val incidentId: UUID,
    val executorActorId: UUID,
    val storeId: UUID?,
) {
    fun scopeIds(): Map<SupportCompensationLimitScope, UUID> =
        buildMap {
            put(SupportCompensationLimitScope.CUSTOMER, customerId)
            orderId?.let { put(SupportCompensationLimitScope.ORDER, it) }
            put(SupportCompensationLimitScope.INCIDENT, incidentId)
            put(SupportCompensationLimitScope.ACTOR, executorActorId)
            storeId?.let { put(SupportCompensationLimitScope.STORE, it) }
        }
}

@Service
internal class SupportCompensationTransactionService(
    private val requests: SupportCompensationRequestJpaRepository,
    private val policyHeads: SupportCompensationPolicyHeadJpaRepository,
    private val policyVersions: SupportCompensationPolicyVersionJpaRepository,
    private val limitRules: SupportCompensationLimitRuleJpaRepository,
    private val limitLocks: SupportCompensationLimitLockJpaRepository,
    private val consumptions: SupportCompensationLimitConsumptionJpaRepository,
    private val terminals: SupportCompensationTerminalBenefitJpaRepository,
    private val idempotencies: SupportCompensationCommandIdempotencyJpaRepository,
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val actionRequests: SupportActionRequestJpaRepository,
    private val actionRevisions: SupportActionRevisionJpaRepository,
    private val investigations: OperationsSupportInvestigationOperations,
    private val permissions: OperatorPermissionAuthorization,
    private val commandLock: SupportCaseCommandLock,
    private val points: GoodwillPointOperations,
    private val coupons: GoodwillCouponOperations,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val payloads: SupportCompensationPayloadCanonicalizer,
    private val clock: Clock,
) {
    private val policy = SupportCompensationPolicy()

    @Transactional
    fun evaluate(
        command: EvaluateSupportCompensationCommand,
        order: GoodwillCompensationOrderFact?,
    ): SupportCompensationEvaluationResource = evaluateCurrent(command.normalized(), order, now()).resource(order?.version ?: 0)

    @Transactional
    fun create(
        raw: CreateSupportCompensationCommand,
        order: GoodwillCompensationOrderFact?,
    ): SupportCompensationResource {
        val command = raw.normalized()
        commandLock.lock(command.caseId, command.actorId, "COMPENSATION_CREATE", command.idempotencyKey)
        val payloadHash = payloads.createHash(command)
        replay(command.actorId, SupportCompensationCommandOperation.CREATE, command.idempotencyKey, payloadHash)?.let {
            return resource(requests.findById(it.compensationRequestId).orElse(null) ?: dependency("Compensation request is missing"))
        }
        val createdAt = now()
        val evaluated = evaluateCurrent(command.evaluation(), order, createdAt)
        if (evaluated.result.decision == SupportCompensationDecision.DENIED || !evaluated.result.executable) policyDenied()
        val compensationId = identifiers.next()
        val route = evaluated.result.approvalRoute
        val actionRequestId = if (route == SupportActionApprovalRoute.NONE) null else identifiers.next()
        val actionRevision =
            actionRequestId?.let {
                SupportActionRevision(
                    id = identifiers.next(),
                    revisionNumber = 1,
                    action = SupportActionType.GOODWILL_COMPENSATION,
                    targetId = compensationId,
                    actionPayloadDigest = payloads.actionDigest(command.evaluation()),
                    verificationSessionId = command.verificationSessionId,
                    policyVersion = evaluated.version.id.toString(),
                    targetVersion = 0,
                    amountKrw = command.amountKrw,
                    reason = "GOODWILL_${evaluated.result.band.name}",
                    evidenceDigest = command.evidenceDigest,
                    expiresAt = evaluated.session.expiresAt,
                    createdByActorId = command.actorId,
                    createdAt = createdAt,
                )
            }
        val actionAggregate =
            actionRevision?.let {
                SupportActionRequest.open(
                    requireNotNull(actionRequestId),
                    command.caseId,
                    command.actorId,
                    command.actorId,
                    route,
                    it,
                )
            }
        actionAggregate?.let { aggregate ->
            actionRequests.saveAndFlush(aggregate.toEntity(createdAt))
            actionRevisions.saveAndFlush(requireNotNull(actionRevision).toEntity(requireNotNull(actionRequestId)))
        }
        val aggregate =
            SupportCompensationRequest.open(
                id = compensationId,
                supportCaseId = command.caseId,
                customerId = evaluated.session.subjectId,
                incidentId = command.incidentId,
                orderId = command.orderId,
                storeId = order?.storeId,
                requesterActorId = command.actorId,
                executorActorId = command.actorId,
                benefitType = command.benefitType,
                amountKrw = command.amountKrw,
                couponTemplateId = command.couponTemplateId,
                policyVersionId = evaluated.version.id,
                band = evaluated.result.band,
                route = route,
                verificationSessionId = command.verificationSessionId,
                targetVersion = order?.version ?: 0,
                costSnapshot = command.evaluation().costSnapshot(),
                payloadDigest = payloads.actionDigest(command.evaluation()),
                evidenceDigest = command.evidenceDigest,
                actionRequestId = actionRequestId,
                createdAt = createdAt,
            )
        val entity = requests.saveAndFlush(aggregate.toEntity(createdAt))
        if (route == SupportActionApprovalRoute.OPERATIONS) {
            investigations.open(
                OpenOperationsSupportInvestigationCommand(
                    requireNotNull(actionRequestId),
                    requireNotNull(actionRevision).id,
                    1,
                    command.actorId,
                    null,
                    command.actorId,
                    actionRevision.expiresAt,
                    createdAt,
                ),
            )
        }
        appendCreationAudits(entity, actionAggregate, createdAt)
        saveIdempotency(
            command.actorId,
            SupportCompensationCommandOperation.CREATE,
            command.idempotencyKey,
            payloadHash,
            entity.id,
            createdAt,
        )
        return resource(entity)
    }

    @Transactional(readOnly = true)
    fun executionOrderBinding(compensationRequestId: UUID): SupportCompensationExecutionBinding {
        val entity = requests.findById(compensationRequestId).orElse(null) ?: notFound()
        return SupportCompensationExecutionBinding(entity.orderId)
    }

    @Transactional
    fun execute(
        raw: ExecuteSupportCompensationCommand,
        order: GoodwillCompensationOrderFact?,
    ): SupportCompensationResource {
        val command = raw.normalized()
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_COMPENSATION_EXECUTE)
        val observed = requests.findById(command.compensationRequestId).orElse(null) ?: notFound()
        commandLock.lock(observed.supportCaseId, command.actorId, "COMPENSATION_EXECUTE", command.idempotencyKey)
        val payloadHash = payloads.executeHash(command)
        replay(command.actorId, SupportCompensationCommandOperation.EXECUTE, command.idempotencyKey, payloadHash)?.let {
            return resource(requests.findById(it.compensationRequestId).orElse(null) ?: dependency("Compensation request is missing"))
        }
        val entity = requests.findLockedById(command.compensationRequestId) ?: notFound()
        if (entity.version != command.expectedRequestVersion || entity.payloadDigest != command.expectedPayloadDigest ||
            entity.targetVersion != command.expectedTargetVersion
        ) {
            stale()
        }
        val actionEntity = exactActionRequest(entity)
        val actionRevision = actionEntity?.let(::currentRevision)
        val effectiveExecutorActorId = actionEntity?.executorActorId ?: entity.executorActorId
        if (command.actorId != effectiveExecutorActorId) denied()
        val executedAt = now()
        val evaluationCommand = entity.evaluationCommand(entity.requesterActorId)
        val evaluated =
            evaluateCurrent(
                evaluationCommand,
                order,
                executedAt,
                caseActorId = effectiveExecutorActorId,
                policyVersionId = entity.policyVersionId,
            )
        if (evaluated.version.id != entity.policyVersionId || evaluated.result.band != entity.band ||
            evaluated.result.approvalRoute != entity.approvalRoute || evaluated.result.decision == SupportCompensationDecision.DENIED ||
            !evaluated.result.executable
        ) {
            stale()
        }
        if (actionEntity != null) {
            if (actionEntity.state != SupportActionRequestState.READY_FOR_EXECUTION || actionEntity.targetId != entity.id ||
                actionEntity.action != SupportActionType.GOODWILL_COMPENSATION ||
                actionRevision?.actionPayloadDigest != entity.payloadDigest
            ) {
                conflict("Compensation approval is not ready or is stale")
            }
        }
        val aggregate = entity.toAggregate()
        if (actionEntity != null) {
            aggregate.executorActorId = effectiveExecutorActorId
            aggregate.markApprovalReady(actionEntity.id, entity.version, executedAt)
        }
        if (command.actorId != aggregate.executorActorId) denied()
        val limitScope = entity.limitScope(effectiveExecutorActorId)
        lockAndCheckLimits(limitScope, entity.amountKrw, evaluated.version.limits, executedAt)
        terminals.findByIncidentId(entity.incidentId)?.let { conflict("Incident already has a terminal goodwill benefit") }
        val owner = issueBenefit(entity, executedAt)
        val benefitChange =
            aggregate.completeBenefit(owner.id, command.actorId, command.expectedPayloadDigest, command.expectedTargetVersion, executedAt)
        if (benefitChange.replayed) conflict("Unexpected compensation benefit replay")
        entity.apply(aggregate, executedAt)
        requests.saveAndFlush(entity)
        if (actionEntity != null) {
            val actionAggregate = actionEntity.toAggregate(requireNotNull(actionRevision))
            actionAggregate.completeCompensationExecution(
                entity.id,
                command.actorId,
                actionRevision.revisionNumber,
                entity.payloadDigest,
                actionRevision.targetVersion,
                executedAt,
            )
            actionEntity.apply(actionAggregate, executedAt)
            actionRequests.saveAndFlush(actionEntity)
        }
        terminals.saveAndFlush(
            SupportCompensationTerminalBenefitEntity(
                owner.id,
                entity.id,
                entity.incidentId,
                entity.benefitType,
                owner.reference,
                entity.amountKrw,
                entity.policyVersionId,
                executedAt,
            ),
        )
        saveConsumptions(entity, limitScope, executedAt)
        audits.appendAll(listOf(benefitAudit(entity, command.actorId, executedAt)))
        saveIdempotency(
            command.actorId,
            SupportCompensationCommandOperation.EXECUTE,
            command.idempotencyKey,
            payloadHash,
            entity.id,
            executedAt,
        )
        return resource(entity)
    }

    @Transactional(readOnly = true)
    fun customerId(compensationRequestId: UUID): UUID = requests.findById(compensationRequestId).orElse(null)?.customerId ?: notFound()

    @Transactional
    fun prepareNotificationRetry(command: RetrySupportCompensationNotificationCommand): SupportCompensationResource {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(normalized.actorId, OperatorPermission.SUPPORT_COMPENSATION_EXECUTE)
        val entity = requests.findLockedById(normalized.compensationRequestId) ?: notFound()
        requireNotificationRetryAuthorization(normalized.actorId, entity)
        replay(
            normalized.actorId,
            SupportCompensationCommandOperation.RETRY_NOTIFICATION,
            normalized.idempotencyKey,
            payloads.retryHash(normalized),
        )?.let { existing ->
            return resource(requests.findById(existing.compensationRequestId).orElse(null) ?: dependency("Compensation request is missing"))
        }
        if (entity.state != SupportCompensationRequestState.NOTIFICATION_RETRY) {
            conflict("Compensation notification is not retryable")
        }
        return resource(entity)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeNotification(
        compensationRequestId: UUID,
        deliveryId: UUID,
        retry: RetrySupportCompensationNotificationCommand?,
        actorId: UUID,
    ): SupportCompensationResource {
        val entity = requests.findLockedById(compensationRequestId) ?: notFound()
        if (entity.state == SupportCompensationRequestState.NOTIFICATION_ACCEPTED && entity.notificationDeliveryId == deliveryId) {
            retry?.let { saveRetryIdempotency(it.normalized(), entity.id, now()) }
            return resource(entity)
        }
        val aggregate = entity.toAggregate()
        val changedAt = now()
        aggregate.completeNotification(deliveryId, changedAt)
        entity.apply(aggregate, changedAt)
        requests.saveAndFlush(entity)
        retry?.let { saveRetryIdempotency(it.normalized(), entity.id, changedAt) }
        return resource(entity)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordNotificationFailure(
        compensationRequestId: UUID,
        retry: RetrySupportCompensationNotificationCommand?,
        actorId: UUID,
        failure: RuntimeException,
    ): SupportCompensationResource {
        val entity = requests.findLockedById(compensationRequestId) ?: notFound()
        val aggregate = entity.toAggregate()
        val changedAt = now()
        val code = if (failure is DomainFailure) failure.code.name else FailureCode.DEPENDENCY_UNAVAILABLE.name
        aggregate.markNotificationRetry(code, changedAt)
        entity.apply(aggregate, changedAt)
        requests.saveAndFlush(entity)
        audits.appendAll(
            listOf(
                audit(
                    actorId,
                    "SUPPORT_COMPENSATION_NOTIFICATION_RETRY",
                    entity.id,
                    "NOTIFICATION_RETRY_SCHEDULED",
                    AuditCategory.OPERATIONS_POLICY,
                    changedAt,
                ),
            ),
        )
        retry?.let { saveRetryIdempotency(it.normalized(), entity.id, changedAt) }
        return resource(entity)
    }

    @Transactional
    fun get(
        actorId: UUID,
        compensationRequestId: UUID,
    ): SupportCompensationResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val entity = requests.findLockedById(compensationRequestId) ?: notFound()
        requireCompensationVisibility(actorId, entity)
        return resource(entity)
    }

    private fun evaluateCurrent(
        command: EvaluateSupportCompensationCommand,
        order: GoodwillCompensationOrderFact?,
        evaluatedAt: Instant,
        caseActorId: UUID = command.actorId,
        policyVersionId: UUID? = null,
    ): EvaluatedCompensation {
        permissions.requireActive(caseActorId, OperatorPermission.SUPPORT_CASE_READ)
        permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_COMPENSATION_REQUEST)
        val supportCase = cases.findLockedById(command.caseId) ?: notFound("SupportCase")
        if (supportCase.currentAssigneeId != caseActorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        val session = sessions.findLockedById(command.verificationSessionId) ?: verificationRequired()
        if (session.actorId != command.actorId || session.supportCaseId != command.caseId ||
            session.subjectType != VerificationSubjectType.CUSTOMER || session.state != VerificationState.VERIFIED ||
            session.actionScope != VerificationActionScope.SUPPORT_ACTION || session.purpose != VerificationPurpose.CASE_RESOLUTION ||
            !evaluatedAt.isBefore(session.expiresAt)
        ) {
            verificationRequired()
        }
        val customerLink = subjectLinks.findByIdAndSupportCaseId(session.subjectLinkId, command.caseId)
        if (customerLink == null || customerLink.unlinkedAt != null || customerLink.subjectType != SupportSubjectType.CUSTOMER ||
            customerLink.subjectId != session.subjectId
        ) {
            denied()
        }
        if (command.orderId != null) {
            if (order == null || order.orderId != command.orderId || order.customerId != session.subjectId || order.currency != "KRW") {
                denied()
            }
            val linked =
                subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                    command.caseId,
                    SupportSubjectType.ORDER,
                    command.orderId,
                    SupportSubjectRelationship.RELATED_ORDER,
                )
            if (!linked) denied()
        } else if (order != null || command.expectedTargetVersion != 0L) {
            invalid("Orderless compensation target version must be zero")
        }
        if (command.benefitType == SupportCompensationBenefitType.COUPON) {
            val templateId = command.couponTemplateId ?: invalid("Coupon template is required")
            val template = coupons.findTemplate(templateId) ?: notFound("CouponTemplate")
            if (template.amountKrw != command.amountKrw || template.validityDays != 30 || order == null) {
                policyDenied()
            }
        } else if (command.couponTemplateId != null) {
            invalid("Point compensation cannot bind a coupon template")
        }
        val version = policyVersionId?.let(::policyVersion) ?: currentPolicyVersion()
        val customerRule = version.limits.single { it.scope == SupportCompensationLimitScope.CUSTOMER }
        val priorCustomerAmount =
            consumptions.sumInWindow(
                SupportCompensationLimitScope.CUSTOMER,
                session.subjectId,
                evaluatedAt.minus(customerRule.window),
            )
        val result =
            policy.evaluate(
                SupportCompensationPolicyInput(
                    command.benefitType,
                    command.amountKrw,
                    command.orderId,
                    order?.payableKrw,
                    order?.storeId,
                    priorCustomerAmount,
                    command.responsibility,
                    session.requestedLevel,
                    terminals.findByIncidentId(command.incidentId) != null,
                    command.expectedTargetVersion == (order?.version ?: 0),
                ),
                version,
                evaluatedAt,
            )
        command.costSnapshot()
        return EvaluatedCompensation(version, result, session)
    }

    private fun currentPolicyVersion(): SupportCompensationPolicyVersion {
        val head = policyHeads.findById(POLICY_HEAD).orElse(null) ?: dependency("Compensation policy head is missing")
        return policyVersion(head.currentVersionId)
    }

    private fun policyVersion(policyVersionId: UUID): SupportCompensationPolicyVersion {
        val version =
            policyVersions.findById(policyVersionId).orElse(null) ?: dependency("Compensation policy version is missing")
        val rules = limitRules.findAllByPolicyVersionId(version.id)
        if (rules.size != SupportCompensationLimitScope.entries.size) dependency("Compensation rolling rules are incomplete")
        return version.toDomain(rules)
    }

    private fun lockAndCheckLimits(
        scope: CompensationLimitScopeSnapshot,
        amountKrw: Long,
        rules: List<SupportCompensationLimitRule>,
        now: Instant,
    ) {
        val scopeIds = scope.scopeIds()
        val byScope = rules.associateBy { it.scope }
        scopeIds.toSortedMap(compareBy { it.ordinal }).forEach { (scope, scopeId) ->
            limitLocks.insertIfAbsent(scope.name, scopeId)
            limitLocks.findLocked(scope, scopeId) ?: dependency("Compensation rolling lock is missing")
            val rule = byScope[scope] ?: dependency("Compensation rolling rule is missing")
            val used = consumptions.sumInWindow(scope, scopeId, now.minus(rule.window))
            val next =
                try {
                    Math.addExact(used, amountKrw)
                } catch (_: ArithmeticException) {
                    policyDenied()
                }
            if (next > rule.maximumKrw) policyDenied()
        }
    }

    private fun saveConsumptions(
        entity: SupportCompensationRequestEntity,
        scope: CompensationLimitScopeSnapshot,
        issuedAt: Instant,
    ) {
        consumptions.saveAllAndFlush(
            scope.scopeIds().map { (limitScope, scopeId) ->
                SupportCompensationLimitConsumptionEntity(
                    identifiers.next(),
                    entity.id,
                    entity.policyVersionId,
                    limitScope,
                    scopeId,
                    entity.amountKrw,
                    issuedAt,
                )
            },
        )
    }

    private fun issueBenefit(
        entity: SupportCompensationRequestEntity,
        issuedAt: Instant,
    ): OwnerBenefit {
        val source = "support-compensation:${entity.id}:${entity.benefitType.name}"
        return when (entity.benefitType) {
            SupportCompensationBenefitType.POINT -> {
                val legs =
                    entity.costSnapshot().fundingLegs(entity.amountKrw, entity.storeId).map { leg ->
                        GoodwillPointFundingLeg(
                            if (leg.issuerType == SupportCompensationFundingIssuer.PLATFORM) {
                                GoodwillPointFundingIssuer.PLATFORM
                            } else {
                                GoodwillPointFundingIssuer.STORE
                            },
                            leg.storeId,
                            leg.amountKrw,
                        )
                    }
                val result =
                    points.issue(
                        IssueGoodwillPointsCommand(
                            entity.id,
                            entity.customerId,
                            entity.amountKrw,
                            legs,
                            entity.policyVersionId,
                            source,
                            entity.payloadDigest,
                            issuedAt,
                            issuedAt.plus(POINT_VALIDITY),
                        ),
                    )
                OwnerBenefit(result.issuanceId, result.sourceReference)
            }

            SupportCompensationBenefitType.COUPON -> {
                val result =
                    coupons.issue(
                        IssueGoodwillCouponCommand(
                            entity.id,
                            entity.customerId,
                            entity.storeId ?: conflict("Coupon compensation requires a related store"),
                            entity.couponTemplateId ?: conflict("Coupon compensation requires a template"),
                            entity.amountKrw,
                            GoodwillCouponResponsibility.valueOf(entity.responsibility.name),
                            entity.platformShareBps,
                            entity.storeShareBps,
                            entity.policyVersionId,
                            source,
                            entity.payloadDigest,
                            issuedAt,
                        ),
                    )
                OwnerBenefit(result.issuanceRecordId, result.sourceReference)
            }
        }
    }

    private fun appendCreationAudits(
        entity: SupportCompensationRequestEntity,
        action: SupportActionRequest?,
        occurredAt: Instant,
    ) {
        val records = mutableListOf<AppendAuditRecordCommand>()
        action?.let {
            records +=
                audit(
                    entity.requesterActorId,
                    "SUPPORT_ACTION_REQUEST_CREATED",
                    it.id,
                    "REQUEST_CREATED",
                    AuditCategory.OPERATIONS_POLICY,
                    occurredAt,
                )
            records +=
                audit(
                    entity.requesterActorId,
                    "SUPPORT_ACTION_REVISION_CREATED",
                    it.id,
                    "REVISION_CREATED",
                    AuditCategory.OPERATIONS_POLICY,
                    occurredAt,
                )
        }
        records +=
            audit(
                entity.requesterActorId,
                "SUPPORT_COMPENSATION_REQUEST_CREATED",
                entity.id,
                "COMPENSATION_REQUEST_CREATED",
                AuditCategory.OPERATIONS_POLICY,
                occurredAt,
            )
        audits.appendAll(records)
    }

    private fun benefitAudit(
        entity: SupportCompensationRequestEntity,
        actorId: UUID,
        occurredAt: Instant,
    ) = audit(
        actorId,
        "SUPPORT_COMPENSATION_BENEFIT_ISSUED",
        entity.id,
        "GOODWILL_BENEFIT_ISSUED",
        AuditCategory.FINANCIAL_TRANSACTION,
        occurredAt,
    )

    private fun audit(
        actorId: UUID,
        action: String,
        targetId: UUID,
        reason: String,
        category: AuditCategory,
        occurredAt: Instant,
    ) = AppendAuditRecordCommand(
        actorId.toString(),
        AuditActorType.PLATFORM_OPERATOR,
        category,
        action,
        "SUPPORT_COMPENSATION",
        targetId,
        occurredAt,
        reason,
        correlationId = correlations.currentOrCreate(),
        sourceReference = "support-compensation:$targetId:$action",
    )

    private fun currentRevision(action: SupportActionRequestEntity): SupportActionRevisionEntity =
        actionRevisions.findByRequestIdAndRevisionNumber(action.id, action.currentRevisionNumber)
            ?: dependency("Action revision is missing")

    private fun exactActionRequest(entity: SupportCompensationRequestEntity): SupportActionRequestEntity? =
        entity.actionRequestId?.let { actionRequestId ->
            val action = actionRequests.findLockedById(actionRequestId) ?: dependency("Action request is missing")
            if (action.targetId != entity.id || action.targetType != SupportActionTargetType.COMPENSATION_REQUEST ||
                action.action != SupportActionType.GOODWILL_COMPENSATION
            ) {
                dependency("Compensation action request binding is invalid")
            }
            action
        }

    private fun requireCompensationVisibility(
        actorId: UUID,
        entity: SupportCompensationRequestEntity,
    ) {
        val supportCase = requireActiveObjectScope(entity)
        val action = exactActionRequest(entity)
        val effectiveExecutorActorId = action?.executorActorId ?: entity.executorActorId
        val visible =
            actorId == entity.requesterActorId ||
                (actorId == effectiveExecutorActorId && actorId == supportCase.currentAssigneeId) ||
                actorId == action?.supportApproverActorId ||
                actorId == action?.operationsApproverActorId
        if (!visible) denied()
    }

    private fun requireNotificationRetryAuthorization(
        actorId: UUID,
        entity: SupportCompensationRequestEntity,
    ) {
        val supportCase = requireActiveObjectScope(entity)
        val effectiveExecutorActorId = exactActionRequest(entity)?.executorActorId ?: entity.executorActorId
        if (actorId != effectiveExecutorActorId || supportCase.currentAssigneeId != actorId) denied()
    }

    private fun requireActiveObjectScope(entity: SupportCompensationRequestEntity): SupportCaseEntity {
        val supportCase = cases.findLockedById(entity.supportCaseId) ?: notFound("SupportCase")
        if (supportCase.state !in ACTIVE_CASE_STATES) denied()
        val session = sessions.findLockedById(entity.verificationSessionId) ?: verificationRequired()
        val customerLink = subjectLinks.findByIdAndSupportCaseId(session.subjectLinkId, entity.supportCaseId)
        if (session.supportCaseId != entity.supportCaseId || session.subjectType != VerificationSubjectType.CUSTOMER ||
            session.subjectId != entity.customerId ||
            customerLink == null || customerLink.unlinkedAt != null || customerLink.subjectType != SupportSubjectType.CUSTOMER ||
            customerLink.subjectId != entity.customerId
        ) {
            denied()
        }
        entity.orderId?.let { orderId ->
            if (!subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                    entity.supportCaseId,
                    SupportSubjectType.ORDER,
                    orderId,
                    SupportSubjectRelationship.RELATED_ORDER,
                )
            ) {
                denied()
            }
        }
        return supportCase
    }

    private fun replay(
        actorId: UUID,
        operation: SupportCompensationCommandOperation,
        key: String,
        hash: String,
    ): SupportCompensationCommandIdempotencyEntity? {
        val existing = idempotencies.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key) ?: return null
        if (existing.payloadHash != hash) throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused")
        return existing
    }

    private fun saveRetryIdempotency(
        command: RetrySupportCompensationNotificationCommand,
        requestId: UUID,
        now: Instant,
    ) {
        val hash = payloads.retryHash(command)
        replay(command.actorId, SupportCompensationCommandOperation.RETRY_NOTIFICATION, command.idempotencyKey, hash)?.let { return }
        saveIdempotency(
            command.actorId,
            SupportCompensationCommandOperation.RETRY_NOTIFICATION,
            command.idempotencyKey,
            hash,
            requestId,
            now,
        )
    }

    private fun saveIdempotency(
        actorId: UUID,
        operation: SupportCompensationCommandOperation,
        key: String,
        hash: String,
        requestId: UUID,
        now: Instant,
    ) {
        idempotencies.saveAndFlush(
            SupportCompensationCommandIdempotencyEntity(
                identifiers.next(),
                actorId,
                operation,
                key,
                hash,
                requestId,
                now,
                now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
    }

    private fun SupportCompensationRequest.toEntity(now: Instant) =
        SupportCompensationRequestEntity(
            id,
            supportCaseId,
            customerId,
            incidentId,
            orderId,
            storeId,
            requesterActorId,
            executorActorId,
            benefitType,
            amountKrw,
            couponTemplateId,
            policyVersionId,
            band,
            route,
            verificationSessionId,
            targetVersion,
            costSnapshot.responsibility,
            costSnapshot.evidenceBasis,
            costSnapshot.evidenceDigest,
            costSnapshot.platformShareBps,
            costSnapshot.storeShareBps,
            payloadDigest,
            evidenceDigest,
            actionRequestId,
            state,
            terminalBenefitId,
            notificationDeliveryId,
            notificationFailureCode,
            now,
            now,
            version,
        )

    private fun SupportActionRequest.toEntity(now: Instant) =
        SupportActionRequestEntity(
            id,
            supportCaseId,
            currentRevision.action,
            SupportActionTargetType.COMPENSATION_REQUEST,
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
        )

    private fun SupportActionRevision.toEntity(requestId: UUID) =
        SupportActionRevisionEntity(
            id,
            requestId,
            revisionNumber,
            action,
            SupportActionTargetType.COMPENSATION_REQUEST,
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

    private fun SupportCompensationRequestEntity.limitScope(executorActorId: UUID) =
        CompensationLimitScopeSnapshot(customerId, orderId, incidentId, executorActorId, storeId)

    private fun SupportCompensationRequestEntity.costSnapshot() =
        SupportCompensationCostSnapshot(responsibility, evidenceBasis, costEvidenceDigest, platformShareBps, storeShareBps)

    private fun SupportCompensationRequestEntity.evaluationCommand(actorId: UUID) =
        EvaluateSupportCompensationCommand(
            actorId,
            supportCaseId,
            incidentId,
            orderId,
            targetVersion,
            benefitType,
            amountKrw,
            couponTemplateId,
            responsibility,
            evidenceBasis,
            costEvidenceDigest,
            platformShareBps,
            storeShareBps,
            verificationSessionId,
        )

    private fun CreateSupportCompensationCommand.normalized() =
        copy(
            idempotencyKey = idempotencyKey.normalizedKey(),
            evidenceDigest = evidenceDigest.normalizedDigest("Evidence"),
            costEvidenceDigest = costEvidenceDigest?.normalizedDigest("Cost evidence"),
        ).also { it.evaluation().normalized() }

    private fun EvaluateSupportCompensationCommand.normalized() =
        copy(costEvidenceDigest = costEvidenceDigest?.normalizedDigest("Cost evidence")).also {
            if (amountKrw <= 0 || expectedTargetVersion < 0 || platformShareBps !in 0..10_000 || storeShareBps !in 0..10_000) {
                invalid("Compensation evaluation fields are invalid")
            }
        }

    private fun ExecuteSupportCompensationCommand.normalized() =
        copy(
            idempotencyKey = idempotencyKey.normalizedKey(),
            expectedPayloadDigest = expectedPayloadDigest.normalizedDigest("Compensation payload"),
        ).also {
            if (expectedRequestVersion < 0 || expectedTargetVersion < 0) invalid("Compensation execution binding is invalid")
        }

    private fun RetrySupportCompensationNotificationCommand.normalized() = copy(idempotencyKey = idempotencyKey.normalizedKey())

    private fun EvaluateSupportCompensationCommand.costSnapshot(): SupportCompensationCostSnapshot =
        try {
            SupportCompensationCostSnapshot(
                responsibility,
                evidenceBasis,
                costEvidenceDigest,
                platformShareBps,
                storeShareBps,
            )
        } catch (_: IllegalArgumentException) {
            invalid("Compensation cost snapshot is invalid")
        }

    private fun resource(entity: SupportCompensationRequestEntity) =
        terminals.findByRequestId(entity.id).let { terminal ->
            SupportCompensationResource(
                entity.id,
                entity.supportCaseId,
                entity.incidentId,
                entity.orderId,
                entity.storeId,
                entity.benefitType,
                entity.amountKrw,
                entity.couponTemplateId,
                entity.policyVersionId,
                entity.band,
                entity.approvalRoute,
                entity.actionRequestId,
                entity.state,
                entity.payloadDigest,
                entity.terminalBenefitId,
                terminal?.issuedAt,
                entity.notificationDeliveryId,
                null,
                entity.notificationFailureCode,
                entity.version,
                entity.createdAt,
                entity.updatedAt,
            )
        }

    private fun EvaluatedCompensation.resource(targetVersion: Long) =
        SupportCompensationEvaluationResource(
            version.id,
            result.band,
            result.decision,
            result.approvalRoute,
            result.requiredVerificationLevel,
            result.executable,
            result.reasonCodes,
            targetVersion,
            result.evaluatedAt,
            result.evaluatedAt.plus(EVALUATION_TTL),
        )

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun String.normalizedKey(): String =
        trim().also {
            if (it != this || it.length !in 8..128 || it.any(Char::isISOControl)) invalid("Idempotency-Key is invalid")
        }

    private fun String.normalizedDigest(name: String): String =
        trim().also {
            if (it != this || !it.matches(SHA_256)) invalid("$name digest is invalid")
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Compensation access is denied")

    private fun notFound(resource: String = "SupportCompensationRequest"): Nothing =
        throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")

    private fun verificationRequired(): Nothing =
        throw DomainFailure(FailureCode.VERIFICATION_REQUIRED, "Current action-bound customer verification is required")

    private fun policyDenied(): Nothing =
        throw DomainFailure(FailureCode.SUPPORT_ACTION_POLICY_DENIED, "Current goodwill compensation policy denies this request")

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Goodwill compensation binding is stale")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STATE_CONFLICT, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        const val POLICY_HEAD = "GOODWILL"
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val EVALUATION_TTL: Duration = Duration.ofMinutes(2)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        val POINT_VALIDITY: Duration = Duration.ofDays(30)
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

private data class EvaluatedCompensation(
    val version: SupportCompensationPolicyVersion,
    val result: SupportCompensationPolicyResult,
    val session: VerificationSessionEntity,
)

private data class OwnerBenefit(
    val id: UUID,
    val reference: String,
)
