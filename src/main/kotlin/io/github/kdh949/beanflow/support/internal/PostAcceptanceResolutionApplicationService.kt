package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.loyalty.api.PostAcceptanceResolutionPointOperations
import io.github.kdh949.beanflow.loyalty.api.RestorePostAcceptanceResolutionPointsCommand
import io.github.kdh949.beanflow.notification.api.PostAcceptanceResolutionNotificationOperations
import io.github.kdh949.beanflow.notification.api.RequestPostAcceptanceResolutionNotificationCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.PostAcceptanceResolutionOrderFact
import io.github.kdh949.beanflow.ordering.api.PostAcceptanceResolutionOrderOperations
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionOrderState
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionPaymentOperations
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionRefundState
import io.github.kdh949.beanflow.payment.api.RequestPostAcceptanceResolutionRefundCommand
import io.github.kdh949.beanflow.payment.api.SchedulePostAcceptanceResolutionRefundReconciliationCommand
import io.github.kdh949.beanflow.promotion.api.PostAcceptanceResolutionCouponOperations
import io.github.kdh949.beanflow.promotion.api.RestorePostAcceptanceResolutionCouponCommand
import io.github.kdh949.beanflow.settlement.api.CreatePostAcceptanceResolutionSettlementAdjustmentCommand
import io.github.kdh949.beanflow.settlement.api.PostAcceptanceResolutionSettlementOperations
import io.github.kdh949.beanflow.settlement.api.PostAcceptanceResolutionSettlementResponsibility
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionCase
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionClaim
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionOutcome
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionPlan
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionResponsibility
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionState
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStepState
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStepType
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class CreatePostAcceptanceResolutionCommand(
    val actorId: UUID,
    val orderId: UUID,
    val requestId: UUID,
    val revisionNumber: Int,
    val expectedRequestVersion: Long,
    val expectedOrderVersion: Long,
    val outcome: PostAcceptanceResolutionOutcome,
    val responsibility: PostAcceptanceResolutionResponsibility,
    val cashRefundKrw: Long,
    val restorePoints: Boolean,
    val restoreCoupon: Boolean,
    val settlementAdjustmentKrw: Long?,
    val evidenceDigest: String,
    val idempotencyKey: String,
)

internal data class ExecutePostAcceptanceResolutionCommand(
    val actorId: UUID,
    val resolutionId: UUID,
    val expectedResolutionVersion: Long,
    val expectedRequestVersion: Long,
    val expectedOrderVersion: Long,
    val idempotencyKey: String,
)

internal data class ReconcilePostAcceptanceResolutionCommand(
    val actorId: UUID,
    val resolutionId: UUID,
    val stepType: PostAcceptanceResolutionStepType,
    val expectedResolutionVersion: Long,
    val expectedOrderVersion: Long,
    val idempotencyKey: String,
)

internal data class PostAcceptanceResolutionStepResource(
    val type: PostAcceptanceResolutionStepType,
    val state: PostAcceptanceResolutionStepState,
    val attemptCount: Int,
    val resultReference: String?,
    val failureCode: String?,
    val nextAttemptAt: Instant?,
    val ownerState: String? = null,
)

internal data class PostAcceptanceResolutionResource(
    val resolutionId: UUID,
    val supportCaseId: UUID,
    val requestId: UUID,
    val revisionNumber: Int,
    val orderId: UUID,
    val triggerOrderState: String,
    val triggerOrderVersion: Long,
    val outcome: PostAcceptanceResolutionOutcome,
    val responsibility: PostAcceptanceResolutionResponsibility,
    val cashRefundKrw: Long,
    val restorePoints: Boolean,
    val restoreCoupon: Boolean,
    val settlementAdjustmentKrw: Long?,
    val state: PostAcceptanceResolutionState,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val steps: List<PostAcceptanceResolutionStepResource>,
)

@Component
internal class PostAcceptanceResolutionPayloadCanonicalizer(
    private val canonicalizer: SupportCommandPayloadCanonicalizer,
) {
    fun actionDigest(command: CreatePostAcceptanceResolutionCommand): String =
        hash(
            canonicalizer.canonical(
                "POST_ACCEPTANCE_RESOLUTION_ACTION_V1",
                listOf(
                    field("orderId", "uuid", command.orderId),
                    field("outcome", "enum", command.outcome),
                    field("responsibility", "enum", command.responsibility),
                    field("cashRefundKrw", "int64", command.cashRefundKrw),
                    field("restorePoints", "boolean", command.restorePoints),
                    field("restoreCoupon", "boolean", command.restoreCoupon),
                    field("settlementAdjustmentKrw", "int64", command.settlementAdjustmentKrw),
                    field("evidenceDigest", "sha256", command.evidenceDigest),
                ),
            ),
        )

    fun createHash(command: CreatePostAcceptanceResolutionCommand): String =
        commandHash(
            "CREATE_POST_ACCEPTANCE_RESOLUTION",
            command.actorId,
            command.requestId,
            command.revisionNumber,
            command.expectedRequestVersion,
            command.expectedOrderVersion,
            actionDigest(command),
        )

    fun executeHash(command: ExecutePostAcceptanceResolutionCommand): String =
        hash(
            canonicalizer.canonical(
                "EXECUTE_POST_ACCEPTANCE_RESOLUTION",
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("resolutionId", "uuid", command.resolutionId),
                    field("expectedResolutionVersion", "int64", command.expectedResolutionVersion),
                    field("expectedRequestVersion", "int64", command.expectedRequestVersion),
                    field("expectedOrderVersion", "int64", command.expectedOrderVersion),
                ),
            ),
        )

    fun reconcileHash(command: ReconcilePostAcceptanceResolutionCommand): String =
        hash(
            canonicalizer.canonical(
                "RECONCILE_POST_ACCEPTANCE_RESOLUTION",
                listOf(
                    field("actorId", "uuid", command.actorId),
                    field("resolutionId", "uuid", command.resolutionId),
                    field("stepType", "enum", command.stepType),
                    field("expectedResolutionVersion", "int64", command.expectedResolutionVersion),
                    field("expectedOrderVersion", "int64", command.expectedOrderVersion),
                ),
            ),
        )

    private fun commandHash(
        operation: String,
        actorId: UUID,
        requestId: UUID,
        revisionNumber: Int,
        requestVersion: Long,
        orderVersion: Long,
        actionDigest: String,
    ): String =
        hash(
            canonicalizer.canonical(
                operation,
                listOf(
                    field("actorId", "uuid", actorId),
                    field("requestId", "uuid", requestId),
                    field("revisionNumber", "int32", revisionNumber),
                    field("expectedRequestVersion", "int64", requestVersion),
                    field("expectedOrderVersion", "int64", orderVersion),
                    field("actionPayloadDigest", "sha256", actionDigest),
                ),
            ),
        )

    private fun field(
        name: String,
        type: String,
        value: Any?,
    ) = SupportCommandPayloadField(name, type, value?.toString())

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
}

@Service
internal class PostAcceptanceResolutionApplicationService(
    private val ordering: PostAcceptanceResolutionOrderOperations,
    private val transactions: PostAcceptanceResolutionTransactionService,
    private val payments: PostAcceptanceResolutionPaymentOperations,
    private val points: PostAcceptanceResolutionPointOperations,
    private val coupons: PostAcceptanceResolutionCouponOperations,
    private val settlement: PostAcceptanceResolutionSettlementOperations,
    private val notifications: PostAcceptanceResolutionNotificationOperations,
    private val clock: Clock,
) {
    fun create(command: CreatePostAcceptanceResolutionCommand): PostAcceptanceResolutionResource {
        val order = ordering.find(command.orderId) ?: conflict("Order is not in a post-acceptance state")
        return transactions.create(command, order)
    }

    fun execute(command: ExecutePostAcceptanceResolutionCommand): PostAcceptanceResolutionResource {
        val order = ordering.find(transactions.orderId(command.resolutionId)) ?: conflict("Order is not in a post-acceptance state")
        transactions.start(command, order)
        advance(command.actorId, command.resolutionId, order)
        return get(command.actorId, command.resolutionId)
    }

    fun reconcile(command: ReconcilePostAcceptanceResolutionCommand): PostAcceptanceResolutionResource {
        if (command.stepType != PostAcceptanceResolutionStepType.PAYMENT_REFUND) {
            throw DomainFailure(FailureCode.REPROCESSING_NOT_SAFE, "Only a Payment Refund supports operator reconciliation")
        }
        val order = ordering.find(transactions.orderId(command.resolutionId)) ?: conflict("Order is not in a post-acceptance state")
        transactions.scheduleReconciliation(command, order)
        advance(command.actorId, command.resolutionId, order)
        return get(command.actorId, command.resolutionId)
    }

    fun get(
        actorId: UUID,
        resolutionId: UUID,
    ): PostAcceptanceResolutionResource {
        val resource = transactions.get(actorId, resolutionId)
        return resource.copy(
            steps =
                resource.steps.map { step ->
                    if (step.type != PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION) return@map step
                    val deliveryId = step.resultReference?.substringAfter("notification:")?.let(::uuidOrNull)
                    val ownerState = deliveryId?.let(notifications::find)?.state
                    step.copy(ownerState = ownerState)
                },
        )
    }

    private fun advance(
        actorId: UUID,
        resolutionId: UUID,
        order: PostAcceptanceResolutionOrderFact,
    ) {
        repeat(PostAcceptanceResolutionStepType.entries.size) {
            val work = transactions.claimNext(actorId, resolutionId, clock.instant()) ?: return
            val outcome = runCatching { executeOwner(work, order) }
            outcome.fold(
                onSuccess = { result -> transactions.recordOwnerResult(actorId, work, result, clock.instant()) },
                onFailure = { failure -> transactions.recordOwnerFailure(actorId, work, failure, clock.instant()) },
            )
            if (outcome.isFailure && outcome.exceptionOrNull() !is DomainFailure) return
        }
    }

    private fun executeOwner(
        work: ClaimedResolutionWork,
        order: PostAcceptanceResolutionOrderFact,
    ): ResolutionOwnerResult =
        when (work.claim.stepType) {
            PostAcceptanceResolutionStepType.PAYMENT_REFUND -> payment(work, order)
            PostAcceptanceResolutionStepType.POINT_RESTORATION -> {
                val result =
                    points.restore(
                        RestorePostAcceptanceResolutionPointsCommand(
                            work.resolutionId,
                            work.orderId,
                            work.resolutionCreatedAt,
                            work.sourceReference,
                            work.payloadHash,
                        ),
                    )
                ResolutionOwnerResult.Success("point-restoration:${result.resultId}:${result.disposition.name}")
            }
            PostAcceptanceResolutionStepType.COUPON_RESTORATION -> {
                val result =
                    coupons.restore(
                        RestorePostAcceptanceResolutionCouponCommand(
                            work.resolutionId,
                            work.orderId,
                            work.resolutionCreatedAt,
                            work.sourceReference,
                            work.payloadHash,
                        ),
                    )
                ResolutionOwnerResult.Success("coupon-restoration:${result.resultId}:${result.disposition.name}")
            }
            PostAcceptanceResolutionStepType.SETTLEMENT_ADJUSTMENT -> {
                val responsibility =
                    when (work.responsibility) {
                        PostAcceptanceResolutionResponsibility.STORE -> PostAcceptanceResolutionSettlementResponsibility.STORE
                        PostAcceptanceResolutionResponsibility.SHARED -> PostAcceptanceResolutionSettlementResponsibility.SHARED
                        else -> conflict("Resolution responsibility cannot create a Settlement adjustment")
                    }
                val result =
                    settlement.create(
                        CreatePostAcceptanceResolutionSettlementAdjustmentCommand(
                            work.resolutionId,
                            work.orderId,
                            order.storeId,
                            responsibility,
                            requireNotNull(work.settlementAdjustmentKrw),
                            work.resolutionCreatedAt,
                            work.sourceReference,
                            work.payloadHash,
                            correlation(work.resolutionId),
                        ),
                    )
                ResolutionOwnerResult.Success("settlement-adjustment:${result.settlementAdjustmentId}")
            }
            PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION -> {
                val result =
                    notifications.request(
                        RequestPostAcceptanceResolutionNotificationCommand(
                            work.resolutionId,
                            work.orderId,
                            order.customerId,
                            order.storeId,
                            work.outcome.name,
                            work.resolutionState.name,
                            work.resolutionCreatedAt,
                            correlation(work.resolutionId),
                        ),
                    )
                ResolutionOwnerResult.Success("notification:${result.deliveryId}")
            }
        }

    private fun payment(
        work: ClaimedResolutionWork,
        order: PostAcceptanceResolutionOrderFact,
    ): ResolutionOwnerResult {
        val view =
            if (work.claim.reconciliation) {
                val existing = payments.findBySourceReference(work.sourceReference) ?: dependency("Resolution Refund is missing")
                if (existing.state == PostAcceptanceResolutionRefundState.FAILED ||
                    existing.state == PostAcceptanceResolutionRefundState.MANUAL_REVIEW
                ) {
                    payments.scheduleReconciliation(
                        SchedulePostAcceptanceResolutionRefundReconciliationCommand(
                            work.resolutionId,
                            existing.refundId,
                            work.sourceReference,
                            work.now,
                        ),
                    )
                }
                payments.execute(existing.refundId, work.now)
            } else {
                val requested =
                    payments.request(
                        RequestPostAcceptanceResolutionRefundCommand(
                            work.resolutionId,
                            work.executorActorId,
                            work.orderId,
                            work.cashRefundKrw,
                            PostAcceptanceResolutionOrderState.valueOf(order.state.name),
                            order.completedAt,
                            order.version,
                            work.sourceReference,
                            work.payloadHash,
                            correlation(work.resolutionId),
                            work.now,
                        ),
                    )
                payments.execute(requested.refundId, work.now)
            }
        return when (view.state) {
            PostAcceptanceResolutionRefundState.SUCCEEDED -> ResolutionOwnerResult.Success("payment-refund:${view.refundId}")
            PostAcceptanceResolutionRefundState.FAILED,
            PostAcceptanceResolutionRefundState.MANUAL_REVIEW,
            -> ResolutionOwnerResult.ManualReview(view.state.name)
            else -> ResolutionOwnerResult.Unknown(view.state.name, work.now.plusSeconds(10))
        }
    }

    private fun correlation(resolutionId: UUID): String = "support-resolution-$resolutionId"

    private fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

internal data class ClaimedResolutionWork(
    val resolutionId: UUID,
    val orderId: UUID,
    val executorActorId: UUID,
    val outcome: PostAcceptanceResolutionOutcome,
    val responsibility: PostAcceptanceResolutionResponsibility,
    val cashRefundKrw: Long,
    val settlementAdjustmentKrw: Long?,
    val resolutionState: PostAcceptanceResolutionState,
    val sourceReference: String,
    val payloadHash: String,
    val claim: PostAcceptanceResolutionClaim,
    val resolutionCreatedAt: Instant,
    val now: Instant,
)

internal sealed interface ResolutionOwnerResult {
    data class Success(val reference: String) : ResolutionOwnerResult
    data class Unknown(val code: String, val retryAt: Instant) : ResolutionOwnerResult
    data class ManualReview(val code: String) : ResolutionOwnerResult
}

@Service
internal class PostAcceptanceResolutionTransactionService(
    private val resolutions: PostAcceptanceResolutionJpaRepository,
    private val steps: PostAcceptanceResolutionStepJpaRepository,
    private val commands: PostAcceptanceResolutionCommandJpaRepository,
    private val requests: SupportActionRequestJpaRepository,
    private val revisions: SupportActionRevisionJpaRepository,
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val payloads: PostAcceptanceResolutionPayloadCanonicalizer,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
    @Value("\${beanflow.support.resolution.claim-lease:PT1M}")
    private val claimLease: Duration,
) {
    @Transactional(readOnly = true)
    fun orderId(resolutionId: UUID): UUID =
        resolutions.findById(resolutionId).orElse(null)?.orderId ?: notFound("PostAcceptanceResolutionCase")

    @Transactional
    fun create(
        command: CreatePostAcceptanceResolutionCommand,
        order: PostAcceptanceResolutionOrderFact,
    ): PostAcceptanceResolutionResource {
        val now = clock.instant()
        val plan =
            try {
                PostAcceptanceResolutionPlan(
                    command.outcome,
                    command.responsibility,
                    command.cashRefundKrw,
                    command.restorePoints,
                    command.restoreCoupon,
                    command.settlementAdjustmentKrw,
                    command.evidenceDigest,
                )
            } catch (failure: IllegalArgumentException) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, failure.message ?: "Resolution plan is invalid")
            }
        val payloadHash = payloads.createHash(command)
        requireExecutionPermissions(command.actorId)
        val existingResolution = resolutions.findByCommandActorIdAndIdempotencyKey(command.actorId, command.idempotencyKey)
        if (existingResolution != null) {
            if (existingResolution.payloadHash != payloadHash) reused()
            val existingRequest = requests.findLockedById(existingResolution.supportActionRequestId) ?: dependency("Resolution request is missing")
            val existingCase = cases.findLockedById(existingResolution.supportCaseId) ?: dependency("Resolution SupportCase is missing")
            if (existingResolution.executorActorId != command.actorId || existingRequest.executorActorId != command.actorId) denied()
            requireCaseScope(existingCase, existingRequest, command.actorId)
            return resource(existingResolution)
        }
        val request = requests.findLockedById(command.requestId) ?: notFound("SupportActionRequest")
        val supportCase = cases.findLockedById(request.supportCaseId) ?: notFound("SupportCase")
        requireRequestAndCase(request, supportCase, command.actorId, command.revisionNumber, command.expectedRequestVersion)
        val revision = currentRevision(request)
        requireRevision(request, revision, command, order, now)
        resolutions.findBySupportActionRequestId(request.id)?.let {
            if (it.payloadHash != payloadHash || it.commandActorId != command.actorId) reused()
            return resource(it)
        }
        val id = identifiers.next()
        val domain =
            PostAcceptanceResolutionCase.plan(
                id,
                supportCase.id,
                request.id,
                revision.id,
                revision.revisionNumber,
                revision.actionPayloadDigest,
                order.orderId,
                order.state.name,
                order.version,
                request.requesterActorId,
                command.actorId,
                plan,
                now,
            )
        val entity = domain.toEntity(command.actorId, command.idempotencyKey, payloadHash)
        resolutions.save(entity)
        steps.saveAllAndFlush(PostAcceptanceResolutionStepType.entries.map { domain.step(it).toEntity(id) })
        audits.appendAll(listOf(audit(command.actorId, id, "SUPPORT_RESOLUTION_PLANNED", "PLANNED", now)))
        return resource(entity)
    }

    @Transactional
    fun start(
        command: ExecutePostAcceptanceResolutionCommand,
        order: PostAcceptanceResolutionOrderFact,
    ) {
        val hash = payloads.executeHash(command)
        var existingCommand =
            commands.findByActorIdAndOperationAndIdempotencyKey(
                command.actorId,
                PostAcceptanceResolutionCommandOperation.EXECUTE,
                command.idempotencyKey,
            )
        existingCommand?.let { if (it.payloadHash != hash || it.resolutionId != command.resolutionId) reused() }
        val observed = resolutions.findById(command.resolutionId).orElse(null) ?: notFound("PostAcceptanceResolutionCase")
        val request = requests.findLockedById(observed.supportActionRequestId) ?: dependency("Resolution request is missing")
        val supportCase = cases.findLockedById(observed.supportCaseId) ?: dependency("Resolution SupportCase is missing")
        val entity = resolutions.findLockedById(command.resolutionId) ?: notFound("PostAcceptanceResolutionCase")
        if (existingCommand == null) {
            existingCommand =
                commands.findByActorIdAndOperationAndIdempotencyKey(
                    command.actorId,
                    PostAcceptanceResolutionCommandOperation.EXECUTE,
                    command.idempotencyKey,
                )
            existingCommand?.let { if (it.payloadHash != hash || it.resolutionId != command.resolutionId) reused() }
        }
        requireExecutionPermissions(command.actorId)
        requireStartScope(entity, request, supportCase, command.actorId)
        if (existingCommand == null && (entity.version != command.expectedResolutionVersion ||
                request.version != command.expectedRequestVersion)
        ) stale()
        requireOrder(order, entity.orderId, command.expectedOrderVersion)
        val revision = currentRevision(request)
        if (!clock.instant().isBefore(revision.expiresAt) && request.state != SupportActionRequestState.EXECUTED) expired()
        if (request.state == SupportActionRequestState.READY_FOR_EXECUTION) {
            val now = clock.instant()
            val aggregate = request.toAggregate(revision)
            try {
                aggregate.completeResolutionExecution(
                    entity.id,
                    command.actorId,
                    entity.revisionNumber,
                    entity.actionPayloadDigest,
                    revision.targetVersion,
                    now,
                )
            } catch (failure: IllegalStateException) {
                conflict(failure.message ?: "Resolution request cannot be consumed")
            }
            request.apply(aggregate, now)
            requests.saveAndFlush(request)
            val resolution = entity.toDomain(steps.findByResolutionIdOrderByStepTypeAsc(entity.id))
            resolution.start(now)
            entity.apply(resolution)
            audits.appendAll(
                listOf(audit(command.actorId, entity.id, "SUPPORT_RESOLUTION_EXECUTION_STARTED", entity.state.name, now)),
            )
        } else if (request.state != SupportActionRequestState.EXECUTED || request.terminalResolutionId != entity.id) {
            conflict("Support action request is not bound to this ResolutionCase")
        }
        if (existingCommand == null) saveCommand(command.actorId, PostAcceptanceResolutionCommandOperation.EXECUTE, command.idempotencyKey, hash, entity.id)
    }

    @Transactional
    fun scheduleReconciliation(
        command: ReconcilePostAcceptanceResolutionCommand,
        order: PostAcceptanceResolutionOrderFact,
    ) {
        val hash = payloads.reconcileHash(command)
        var existingCommand =
            commands.findByActorIdAndOperationAndIdempotencyKey(
                command.actorId,
                PostAcceptanceResolutionCommandOperation.RECONCILE,
                command.idempotencyKey,
            )
        existingCommand?.let { if (it.payloadHash != hash || it.resolutionId != command.resolutionId) reused() }
        val observed = resolutions.findById(command.resolutionId).orElse(null) ?: notFound("PostAcceptanceResolutionCase")
        val request = requests.findLockedById(observed.supportActionRequestId) ?: dependency("Resolution request is missing")
        val supportCase = cases.findLockedById(observed.supportCaseId) ?: dependency("Resolution SupportCase is missing")
        val entity = resolutions.findLockedById(command.resolutionId) ?: notFound("PostAcceptanceResolutionCase")
        requireExecutionPermissions(command.actorId)
        requireResolutionScope(entity, request, supportCase, command.actorId)
        if (existingCommand == null) {
            existingCommand =
                commands.findByActorIdAndOperationAndIdempotencyKey(
                    command.actorId,
                    PostAcceptanceResolutionCommandOperation.RECONCILE,
                    command.idempotencyKey,
                )
            existingCommand?.let { if (it.payloadHash != hash || it.resolutionId != command.resolutionId) reused() }
        }
        if (existingCommand != null) return
        if (entity.version != command.expectedResolutionVersion) stale()
        requireOrder(order, entity.orderId, command.expectedOrderVersion)
        val stepEntities = steps.findByResolutionIdOrderByStepTypeAsc(entity.id)
        val domain = entity.toDomain(stepEntities)
        val step = domain.step(command.stepType)
        if (step.state == PostAcceptanceResolutionStepState.MANUAL_REVIEW) {
            domain.scheduleManualReconciliation(command.stepType, clock.instant())
            entity.apply(domain)
            stepEntities.single { it.stepType == command.stepType }.apply(step)
        } else if (step.state != PostAcceptanceResolutionStepState.UNKNOWN) {
            throw DomainFailure(FailureCode.REPROCESSING_NOT_SAFE, "Resolution step is not safely reconcilable")
        }
        saveCommand(command.actorId, PostAcceptanceResolutionCommandOperation.RECONCILE, command.idempotencyKey, hash, entity.id)
        audits.appendAll(
            listOf(audit(command.actorId, entity.id, "SUPPORT_RESOLUTION_RECONCILIATION_SCHEDULED", command.stepType.name, clock.instant())),
        )
    }

    @Transactional
    fun claimNext(
        actorId: UUID,
        resolutionId: UUID,
        now: Instant,
    ): ClaimedResolutionWork? {
        val observed = resolutions.findById(resolutionId).orElse(null) ?: notFound("PostAcceptanceResolutionCase")
        val request = requests.findLockedById(observed.supportActionRequestId) ?: dependency("Resolution request is missing")
        val supportCase = cases.findLockedById(observed.supportCaseId) ?: dependency("Resolution SupportCase is missing")
        val entity = resolutions.findLockedById(resolutionId) ?: notFound("PostAcceptanceResolutionCase")
        requireExecutionPermissions(actorId)
        requireResolutionScope(entity, request, supportCase, actorId)
        val stepEntities = steps.findByResolutionIdOrderByStepTypeAsc(entity.id)
        val domain = entity.toDomain(stepEntities)
        stepEntities.filter { it.state in PROCESSING_STATES && it.claimUntil?.let { until -> !now.isBefore(until) } == true }
            .forEach { expiredStep ->
                domain.recoverExpiredClaim(expiredStep.stepType, now)
                expiredStep.apply(domain.step(expiredStep.stepType))
            }
        val financial =
            FINANCIAL_STEP_ORDER.mapNotNull { type ->
                stepEntities.singleOrNull { it.stepType == type }
            }
        val selected =
            financial.firstOrNull { it.claimableAt(now) }
                ?: stepEntities.single { it.stepType == PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION }
                    .takeIf { domain.state in TERMINAL_FINANCIAL_STATES && it.claimableAt(now) }
                ?: return null
        val claim = domain.claim(selected.stepType, identifiers.next(), now, claimLease)
        selected.apply(domain.step(selected.stepType))
        entity.apply(domain)
        return ClaimedResolutionWork(
            entity.id,
            entity.orderId,
            entity.executorActorId,
            entity.outcome,
            entity.responsibility,
            entity.cashRefundKrw,
            entity.settlementAdjustmentKrw,
            domain.state,
            selected.sourceReference,
            selected.payloadHash,
            claim,
            entity.createdAt,
            now,
        )
    }

    @Transactional
    fun recordOwnerResult(
        actorId: UUID,
        work: ClaimedResolutionWork,
        result: ResolutionOwnerResult,
        now: Instant,
    ) {
        val entity = resolutions.findLockedById(work.resolutionId) ?: dependency("ResolutionCase is missing")
        val stepEntity = steps.findLocked(work.resolutionId, work.claim.stepType) ?: dependency("Resolution step is missing")
        val allSteps = steps.findByResolutionIdOrderByStepTypeAsc(entity.id)
        val domain = entity.toDomain(allSteps.map { if (it.id == stepEntity.id) stepEntity else it })
        when (result) {
            is ResolutionOwnerResult.Success -> domain.recordSuccess(work.claim.stepType, work.claim.claimToken, result.reference, now)
            is ResolutionOwnerResult.Unknown -> domain.recordUnknown(work.claim.stepType, work.claim.claimToken, result.code, now, result.retryAt)
            is ResolutionOwnerResult.ManualReview -> domain.recordManualReview(work.claim.stepType, work.claim.claimToken, result.code, now)
        }
        entity.apply(domain)
        stepEntity.apply(domain.step(work.claim.stepType))
        audits.appendAll(
            listOf(
                audit(
                    actorId,
                    entity.id,
                    "SUPPORT_RESOLUTION_STEP_RECORDED",
                    "${work.claim.stepType.name}:${domain.step(work.claim.stepType).state.name}:attempt-${domain.step(work.claim.stepType).attemptCount}",
                    now,
                ),
            ),
        )
    }

    @Transactional
    fun recordOwnerFailure(
        actorId: UUID,
        work: ClaimedResolutionWork,
        failure: Throwable,
        now: Instant,
    ) {
        val code = (failure as? DomainFailure)?.code
        val result =
            if (code in RETRYABLE_FAILURES || failure !is DomainFailure) {
                ResolutionOwnerResult.Unknown(code?.name ?: "DEPENDENCY_UNAVAILABLE", now.plusSeconds(30))
            } else {
                ResolutionOwnerResult.ManualReview(code?.name ?: "OWNER_REJECTED")
            }
        recordOwnerResult(actorId, work, result, now)
    }

    @Transactional
    fun get(
        actorId: UUID,
        resolutionId: UUID,
    ): PostAcceptanceResolutionResource {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val entity = resolutions.findLockedById(resolutionId) ?: notFound("PostAcceptanceResolutionCase")
        val request = requests.findById(entity.supportActionRequestId).orElse(null) ?: dependency("Resolution request is missing")
        val visible =
            actorId == request.requesterActorId || actorId == request.executorActorId ||
                permissions.hasActive(actorId, OperatorPermission.SUPPORT_ACTION_APPROVE) ||
                permissions.hasActive(actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        if (!visible) denied()
        return resource(entity)
    }

    private fun requireRequestAndCase(
        request: SupportActionRequestEntity,
        supportCase: SupportCaseEntity,
        actorId: UUID,
        revisionNumber: Int,
        expectedRequestVersion: Long,
    ) {
        if (request.action != SupportActionType.POST_ACCEPTANCE_RESOLUTION ||
            request.currentRevisionNumber != revisionNumber || request.version != expectedRequestVersion
        ) stale()
        if (request.executorActorId != actorId || request.state != SupportActionRequestState.READY_FOR_EXECUTION) conflict("Request is not ready")
        if (actorId in setOfNotNull(request.requesterActorId, request.supportApproverActorId, request.operationsApproverActorId)) denied()
        requireCaseScope(supportCase, request, actorId)
        if (!permissions.hasActive(request.requesterActorId, OperatorPermission.SUPPORT_ACTION_REQUEST) ||
            !permissions.hasActive(request.requesterActorId, OperatorPermission.SUPPORT_RESOLUTION_REQUEST)
        ) stale()
    }

    private fun requireRevision(
        request: SupportActionRequestEntity,
        revision: SupportActionRevisionEntity,
        command: CreatePostAcceptanceResolutionCommand,
        order: PostAcceptanceResolutionOrderFact,
        now: Instant,
    ) {
        if (!now.isBefore(revision.expiresAt)) expired()
        if (revision.policyVersion != SupportActionPolicy.POLICY_VERSION || revision.targetId != command.orderId ||
            revision.targetVersion != command.expectedOrderVersion || revision.amountKrw != command.cashRefundKrw ||
            revision.evidenceDigest != command.evidenceDigest || revision.actionPayloadDigest != payloads.actionDigest(command)
        ) stale()
        requireOrder(order, command.orderId, command.expectedOrderVersion)
        val session = sessions.findLockedById(revision.verificationSessionId) ?: stale()
        if (session.actorId != request.requesterActorId || session.supportCaseId != request.supportCaseId ||
            session.state != VerificationState.VERIFIED || session.actionScope != VerificationActionScope.SUPPORT_ACTION ||
            session.purpose != VerificationPurpose.CASE_RESOLUTION || session.expiresAt != revision.expiresAt ||
            !now.isBefore(session.expiresAt)
        ) stale()
        val link = subjectLinks.findByIdAndSupportCaseId(session.subjectLinkId, request.supportCaseId)
        if (link == null || link.unlinkedAt != null || link.subjectId != session.subjectId) stale()
    }

    private fun requireResolutionScope(
        resolution: PostAcceptanceResolutionEntity,
        request: SupportActionRequestEntity,
        supportCase: SupportCaseEntity,
        actorId: UUID,
    ) {
        if (resolution.executorActorId != actorId || request.executorActorId != actorId ||
            request.terminalResolutionId != resolution.id || request.state != SupportActionRequestState.EXECUTED
        ) denied()
        requireCaseScope(supportCase, request, actorId)
    }

    private fun requireStartScope(
        resolution: PostAcceptanceResolutionEntity,
        request: SupportActionRequestEntity,
        supportCase: SupportCaseEntity,
        actorId: UUID,
    ) {
        if (resolution.executorActorId != actorId || request.executorActorId != actorId) denied()
        val consumable = request.state == SupportActionRequestState.READY_FOR_EXECUTION && request.terminalResolutionId == null
        val replay = request.state == SupportActionRequestState.EXECUTED && request.terminalResolutionId == resolution.id
        if (!consumable && !replay) conflict("Support action request is not bound to an executable ResolutionCase")
        requireCaseScope(supportCase, request, actorId)
    }

    private fun requireCaseScope(
        supportCase: SupportCaseEntity,
        request: SupportActionRequestEntity,
        actorId: UUID,
    ) {
        if (supportCase.currentAssigneeId != actorId || supportCase.state !in ACTIVE_CASE_STATES) denied()
        if (!subjectLinks.existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
                supportCase.id,
                SupportSubjectType.ORDER,
                request.targetId,
                SupportSubjectRelationship.RELATED_ORDER,
            )
        ) denied()
    }

    private fun requireExecutionPermissions(actorId: UUID) {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ACTION_EXECUTE)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_RESOLUTION_EXECUTE)
    }

    private fun requireOrder(
        order: PostAcceptanceResolutionOrderFact,
        orderId: UUID,
        expectedVersion: Long,
    ) {
        if (order.orderId != orderId || order.version != expectedVersion) stale()
    }

    private fun currentRevision(request: SupportActionRequestEntity): SupportActionRevisionEntity =
        revisions.findByRequestIdAndRevisionNumber(request.id, request.currentRevisionNumber) ?: dependency("Action revision is missing")

    private fun resource(entity: PostAcceptanceResolutionEntity): PostAcceptanceResolutionResource =
        PostAcceptanceResolutionResource(
            entity.id,
            entity.supportCaseId,
            entity.supportActionRequestId,
            entity.revisionNumber,
            entity.orderId,
            entity.triggerOrderState,
            entity.triggerOrderVersion,
            entity.outcome,
            entity.responsibility,
            entity.cashRefundKrw,
            entity.restorePoints,
            entity.restoreCoupon,
            entity.settlementAdjustmentKrw,
            entity.state,
            entity.version,
            entity.createdAt,
            entity.updatedAt,
            steps.findByResolutionIdOrderByStepTypeAsc(entity.id).map {
                PostAcceptanceResolutionStepResource(
                    it.stepType,
                    it.state,
                    it.attemptCount,
                    it.resultReference,
                    it.failureCode,
                    it.nextAttemptAt,
                )
            },
        )

    private fun PostAcceptanceResolutionCase.toEntity(
        commandActorId: UUID,
        idempotencyKey: String,
        payloadHash: String,
    ) = PostAcceptanceResolutionEntity(
        id,
        supportCaseId,
        supportActionRequestId,
        supportActionRevisionId,
        revisionNumber,
        SupportActionType.POST_ACCEPTANCE_RESOLUTION,
        actionPayloadDigest,
        orderId,
        triggerOrderState,
        triggerOrderVersion,
        requesterActorId,
        commandActorId,
        executorActorId,
        plan.outcome,
        plan.responsibility,
        plan.cashRefundKrw,
        plan.restorePoints,
        plan.restoreCoupon,
        plan.settlementAdjustmentKrw,
        plan.evidenceDigest,
        idempotencyKey,
        payloadHash,
        state,
        createdAt,
        updatedAt,
        createdAt.plus(Duration.ofDays(90)),
        version,
    )

    private fun io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStep.toEntity(resolutionId: UUID) =
        PostAcceptanceResolutionStepEntity(
            id,
            resolutionId,
            type,
            state,
            sourceReference,
            payloadHash,
            attemptCount,
            nextAttemptAt,
            resultReference,
            failureCode,
            claimToken,
            claimUntil,
            updatedAt,
            version,
        )

    private fun saveCommand(
        actorId: UUID,
        operation: PostAcceptanceResolutionCommandOperation,
        idempotencyKey: String,
        hash: String,
        resolutionId: UUID,
    ) {
        val now = clock.instant()
        commands.saveAndFlush(
            PostAcceptanceResolutionCommandEntity(
                identifiers.next(),
                actorId,
                operation,
                idempotencyKey,
                hash,
                resolutionId,
                now,
                now.plus(Duration.ofDays(90)),
            ),
        )
    }

    private fun audit(
        actorId: UUID,
        resolutionId: UUID,
        action: String,
        state: String,
        now: Instant,
    ) = AppendAuditRecordCommand(
        actorId = actorId.toString(),
        actorType = AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.ORDER_AND_FULFILLMENT,
        action = action,
        targetType = "POST_ACCEPTANCE_RESOLUTION",
        targetId = resolutionId,
        occurredAt = now,
        reason = state,
        afterSummary = mapOf("state" to state),
        correlationId = "support-resolution-$resolutionId",
        sourceReference = "support-resolution:$resolutionId:audit:$action:$state",
    )

    private fun PostAcceptanceResolutionStepEntity.claimableAt(now: Instant): Boolean =
        state in CLAIMABLE_STATES && nextAttemptAt?.let { !now.isBefore(it) } == true

    private fun notFound(resource: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "$resource was not found")
    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Resolution binding is stale")
    private fun expired(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED, "Resolution approval expired")
    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STATE_CONFLICT, message)
    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Resolution access is denied")
    private fun reused(): Nothing = throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Resolution idempotency key was reused")

    private companion object {
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val CLAIMABLE_STATES =
            setOf(
                PostAcceptanceResolutionStepState.PENDING,
                PostAcceptanceResolutionStepState.RETRY_SCHEDULED,
                PostAcceptanceResolutionStepState.UNKNOWN,
            )
        val PROCESSING_STATES =
            setOf(PostAcceptanceResolutionStepState.PROCESSING, PostAcceptanceResolutionStepState.RECONCILING)
        val TERMINAL_FINANCIAL_STATES =
            setOf(
                PostAcceptanceResolutionState.PARTIALLY_RESOLVED,
                PostAcceptanceResolutionState.RESOLVED,
                PostAcceptanceResolutionState.MANUAL_REVIEW,
            )
        val RETRYABLE_FAILURES =
            setOf(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                FailureCode.PAYMENT_REFUND_UNRESOLVED,
                FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
            )
        val FINANCIAL_STEP_ORDER =
            listOf(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                PostAcceptanceResolutionStepType.POINT_RESTORATION,
                PostAcceptanceResolutionStepType.COUPON_RESTORATION,
                PostAcceptanceResolutionStepType.SETTLEMENT_ADJUSTMENT,
            )
    }
}
