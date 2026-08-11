package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileRevealOperations
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileRevealOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileRevealOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassReasonCode
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassRequest
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassReviewDecision
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassState
import io.github.kdh949.beanflow.support.internal.domain.DataAccessBinding
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class RequestBreakGlassCommand(
    val actorId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val field: SupportPersonalDataField,
    val purpose: VerificationPurpose,
    val reasonCode: BreakGlassReasonCode,
    val idempotencyKey: String,
    val correlationId: String,
)

internal enum class BreakGlassApprovalDecision {
    APPROVE,
    DENY,
}

internal data class DecideBreakGlassCommand(
    val actorId: UUID,
    val requestId: UUID,
    val decision: BreakGlassApprovalDecision,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class RevealBreakGlassCommand(
    val actorId: UUID,
    val requestId: UUID,
    val field: SupportPersonalDataField,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class ReviewBreakGlassCommand(
    val actorId: UUID,
    val requestId: UUID,
    val decision: BreakGlassReviewDecision,
    val expectedVersion: Long,
    val reasonCode: String,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class BreakGlassResource(
    val requestId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val field: SupportPersonalDataField,
    val purpose: VerificationPurpose,
    val reasonCode: BreakGlassReasonCode,
    val state: BreakGlassState,
    val requestedAt: Instant,
    val expiresAt: Instant?,
    val version: Long,
)

internal class BreakGlassRevealResource(
    val revealAttemptId: UUID,
    val requestId: UUID,
    val caseId: UUID,
    val subjectId: UUID,
    val field: SupportPersonalDataField,
    val value: String,
    val revealedAt: Instant,
) {
    override fun toString(): String =
        "BreakGlassRevealResource(revealAttemptId=$revealAttemptId, requestId=$requestId, caseId=$caseId, " +
            "subjectId=$subjectId, field=$field, value=<redacted>, revealedAt=$revealedAt)"
}

internal data class BreakGlassRevealWork(
    val attemptId: UUID,
    val idempotencyId: UUID,
    val requestId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val actorId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val field: SupportPersonalDataField,
)

@Service
internal class BreakGlassApplicationService(
    private val transactions: BreakGlassTransactions,
    private val customers: CustomerSupportProfileRevealOperations,
    private val stores: StoreSupportProfileRevealOperations,
    private val couriers: ExternalCourierSupportProfileRevealOperations,
) {
    fun request(command: RequestBreakGlassCommand): BreakGlassResource = transactions.request(command)

    fun decide(command: DecideBreakGlassCommand): BreakGlassResource = transactions.decide(command)

    fun review(command: ReviewBreakGlassCommand): BreakGlassResource = transactions.review(command)

    fun reveal(command: RevealBreakGlassCommand): BreakGlassRevealResource {
        val work = transactions.reserveReveal(command)
        val ownerField = work.field.toOwnerField()
        try {
            val response =
                when (work.subjectType) {
                    VerificationSubjectType.CUSTOMER -> customers.reveal(RevealPersonalDataCommand(work.subjectId, setOf(ownerField)))
                    VerificationSubjectType.STORE -> stores.reveal(RevealPersonalDataCommand(work.subjectId, setOf(ownerField)))
                    VerificationSubjectType.DELIVERY -> couriers.reveal(RevealPersonalDataCommand(work.subjectId, setOf(ownerField)))
                }
            validateOwnerResponse(work, response)
            val value =
                response.values[ownerField]
                    ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Break-glass owner response is incomplete")
            val completedAt = transactions.completeReveal(work)
            return BreakGlassRevealResource(
                work.attemptId,
                work.requestId,
                work.caseId,
                work.subjectId,
                work.field,
                value,
                completedAt,
            )
        } catch (failure: RuntimeException) {
            try {
                transactions.failReveal(work.attemptId, failure.failureClass())
            } catch (recordingFailure: RuntimeException) {
                failure.addSuppressed(recordingFailure)
            }
            if (failure is DomainFailure) throw failure
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Break-glass owner reveal is unavailable").also {
                it.initCause(failure)
            }
        }
    }

    private fun validateOwnerResponse(
        work: BreakGlassRevealWork,
        response: RevealedPersonalData,
    ) {
        if (response.subjectId != work.subjectId || response.values.keys != setOf(work.field.toOwnerField())) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Break-glass owner response is invalid")
        }
    }

    private fun RuntimeException.failureClass(): String =
        if (this is DomainFailure && code != FailureCode.DEPENDENCY_UNAVAILABLE) "REVEAL_COMPLETION_REJECTED" else "OWNER_REVEAL_FAILED"
}

@Service
internal class BreakGlassTransactions(
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val requests: BreakGlassRequestJpaRepository,
    private val decisions: BreakGlassDecisionJpaRepository,
    private val notifications: SecurityNotificationIntentJpaRepository,
    private val revealAttempts: RevealAttemptJpaRepository,
    private val revealFields: RevealAttemptFieldJpaRepository,
    private val idempotency: SupportSecurityIdempotencyJpaRepository,
    private val commandLock: SupportCaseCommandLock,
    private val permissions: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun request(command: RequestBreakGlassCommand): BreakGlassResource =
        boundary {
            validateKey(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_BREAK_GLASS_REQUEST)
            commandLock.lock(command.caseId, command.actorId, REQUEST_BREAK_GLASS, command.idempotencyKey)
            replayOrExecute(
                command.actorId,
                REQUEST_BREAK_GLASS,
                command.idempotencyKey,
                hash("${command.caseId}|${command.subjectLinkId}|${command.field}|${command.purpose}|${command.reasonCode}"),
                201,
            ) {
                val supportCase = activeAssignedCase(command.caseId, command.actorId)
                val link = activeLink(supportCase.id, command.subjectLinkId)
                val aggregate =
                    BreakGlassRequest.request(
                        identifiers.next(),
                        supportCase.id,
                        link.id,
                        link.subjectType.toBreakGlassSubjectType(),
                        link.subjectId,
                        command.actorId,
                        command.field,
                        command.purpose,
                        command.reasonCode,
                        clock.instant(),
                    )
                val entity = aggregate.toEntity()
                requests.saveAndFlush(entity)
                notification(entity.id, "REQUESTED", entity.requestedAt)
                audits.appendAll(
                    listOf(entity.audit("SUPPORT_BREAK_GLASS_REQUESTED", command.actorId, command.correlationId, entity.requestedAt)),
                )
                entity.toResource()
            }
        }

    @Transactional
    fun decide(command: DecideBreakGlassCommand): BreakGlassResource =
        boundary {
            validateKey(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)
            val caseId = requests.findCaseIdById(command.requestId) ?: notFound()
            commandLock.lock(caseId, command.actorId, DECIDE_BREAK_GLASS, command.idempotencyKey)
            activeCase(caseId)
            replayOrExecute(
                command.actorId,
                DECIDE_BREAK_GLASS,
                command.idempotencyKey,
                hash("${command.requestId}|${command.decision}|${command.expectedVersion}"),
                200,
            ) {
                val entity = requests.findLockedById(command.requestId) ?: notFound()
                if (entity.supportCaseId != caseId) conflict("Break-glass request binding is stale")
                if (entity.version != command.expectedVersion) conflict("Break-glass request version is stale")
                val aggregate = entity.toAggregate()
                val now = clock.instant()
                when (command.decision) {
                    BreakGlassApprovalDecision.APPROVE -> aggregate.approve(command.actorId, now)
                    BreakGlassApprovalDecision.DENY -> aggregate.deny(command.actorId)
                }
                entity.apply(aggregate, now)
                requests.saveAndFlush(entity)
                decisions.saveAndFlush(
                    BreakGlassDecisionEntity(
                        identifiers.next(),
                        entity.id,
                        "PRE_APPROVAL",
                        command.actorId,
                        if (command.decision == BreakGlassApprovalDecision.APPROVE) "APPROVED" else "DENIED",
                        entity.reasonCode.name,
                        entity.version,
                        now,
                    ),
                )
                if (command.decision == BreakGlassApprovalDecision.APPROVE) notification(entity.id, "APPROVED", now)
                audits.appendAll(listOf(entity.audit("SUPPORT_BREAK_GLASS_DECIDED", command.actorId, command.correlationId, now)))
                entity.toResource()
            }
        }

    @Transactional
    fun reserveReveal(command: RevealBreakGlassCommand): BreakGlassRevealWork =
        boundary {
            validateKey(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_BREAK_GLASS_REQUEST)
            val caseId = requests.findCaseIdById(command.requestId) ?: notFound()
            commandLock.lock(caseId, command.actorId, REVEAL_BREAK_GLASS, command.idempotencyKey)
            val supportCase = activeAssignedCase(caseId, command.actorId)
            val payloadHash = hash("${command.requestId}|${command.field}")
            idempotency.findByActorIdAndOperationAndIdempotencyKey(command.actorId, REVEAL_BREAK_GLASS, command.idempotencyKey)?.let {
                requirePayload(it, payloadHash)
                throw DomainFailure(
                    FailureCode.IDEMPOTENCY_MANUAL_REVIEW_REQUIRED,
                    "Raw break-glass responses are not persisted or replayed",
                )
            }
            val entity = requests.findLockedById(command.requestId) ?: notFound()
            if (entity.supportCaseId != caseId) conflict("Break-glass request binding is stale")
            if (entity.requesterId !=
                command.actorId
            ) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Break-glass request belongs to another operator")
            }
            val link = activeLink(caseId, entity.subjectLinkId)
            if (link.subjectType.toBreakGlassSubjectType() != entity.subjectType || link.subjectId != entity.subjectId) {
                conflict("Break-glass subject binding is stale")
            }
            val aggregate = entity.toAggregate()
            val now = clock.instant()
            aggregate.reserveReveal(
                command.actorId,
                DataAccessBinding(supportCase.id, entity.subjectLinkId, entity.subjectId, entity.purpose),
                command.field,
                now,
            )
            entity.apply(aggregate, now)
            requests.saveAndFlush(entity)
            val attemptId = identifiers.next()
            revealAttempts.saveAndFlush(
                RevealAttemptEntity(
                    attemptId,
                    "BREAK_GLASS",
                    null,
                    entity.id,
                    supportCase.id,
                    entity.subjectLinkId,
                    entity.subjectType,
                    entity.subjectId,
                    command.actorId,
                    entity.purpose,
                    "RESERVED",
                    null,
                    now,
                    null,
                ),
            )
            revealFields.saveAndFlush(RevealAttemptFieldEntity(attemptId, command.field))
            val idempotencyEntity = processing(command.actorId, REVEAL_BREAK_GLASS, command.idempotencyKey, payloadHash, attemptId, now)
            idempotency.saveAndFlush(idempotencyEntity)
            notification(entity.id, "REVEALED", now)
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = command.actorId.toString(),
                        actorType = AuditActorType.PLATFORM_OPERATOR,
                        category = AuditCategory.PII_ACCESS,
                        action = "SUPPORT_PII_ACCESS_RECORDED",
                        targetType = "SUPPORT_REVEAL_ATTEMPT",
                        targetId = attemptId,
                        occurredAt = now,
                        reason = entity.reasonCode.name,
                        afterSummary =
                            mapOf(
                                "event" to "SUPPORT_PII_ACCESS_RECORDED",
                                "accessPath" to "BREAK_GLASS",
                                "fieldCount" to "1",
                                "postReview" to "REQUIRED",
                            ),
                        correlationId = command.correlationId,
                        sourceReference = "support-break-glass-reveal:$attemptId",
                    ),
                ),
            )
            BreakGlassRevealWork(
                attemptId,
                idempotencyEntity.id,
                entity.id,
                supportCase.id,
                entity.subjectLinkId,
                command.actorId,
                entity.subjectType,
                entity.subjectId,
                command.field,
            )
        }

    @Transactional
    fun completeReveal(work: BreakGlassRevealWork): Instant =
        boundary {
            activeAssignedCase(work.caseId, work.actorId)
            val link = activeLink(work.caseId, work.subjectLinkId)
            if (link.subjectType.toBreakGlassSubjectType() != work.subjectType || link.subjectId != work.subjectId) {
                conflict("Break-glass subject binding is stale")
            }
            permissions.requireActive(work.actorId, OperatorPermission.SUPPORT_BREAK_GLASS_REQUEST)
            val attempt = revealAttempts.findLockedById(work.attemptId) ?: notFound()
            if (attempt.actorId != work.actorId || attempt.supportCaseId != work.caseId) conflict("RevealAttempt binding is stale")
            val now = clock.instant()
            attempt.revealed(now)
            revealAttempts.saveAndFlush(attempt)
            completeCommand(work.idempotencyId, mapOf("revealAttemptId" to work.attemptId.toString(), "state" to "REVEALED"), now)
            now
        }

    @Transactional
    fun failReveal(
        attemptId: UUID,
        failureClass: String,
    ) {
        boundary {
            val attempt = revealAttempts.findLockedById(attemptId) ?: notFound()
            if (attempt.state == "RESERVED") {
                attempt.failed(failureClass, clock.instant())
                revealAttempts.saveAndFlush(attempt)
            }
        }
    }

    @Transactional
    fun review(command: ReviewBreakGlassCommand): BreakGlassResource =
        boundary {
            validateKey(command.idempotencyKey)
            val normalizedReason = command.reasonCode.trim()
            if (normalizedReason.length !in 1..32 || !normalizedReason.matches(Regex("^[A-Z][A-Z0-9_]*$"))) invalid()
            permissions.requireActive(command.actorId, OperatorPermission.PRIVACY_BREAK_GLASS_REVIEW)
            val caseId = requests.findCaseIdById(command.requestId) ?: notFound()
            commandLock.lock(caseId, command.actorId, REVIEW_BREAK_GLASS, command.idempotencyKey)
            cases.findLockedById(caseId) ?: notFound()
            replayOrExecute(
                command.actorId,
                REVIEW_BREAK_GLASS,
                command.idempotencyKey,
                hash("${command.requestId}|${command.decision}|${command.expectedVersion}|$normalizedReason"),
                200,
            ) {
                val entity = requests.findLockedById(command.requestId) ?: notFound()
                if (entity.supportCaseId != caseId) conflict("Break-glass request binding is stale")
                if (entity.version != command.expectedVersion) conflict("Break-glass request version is stale")
                val aggregate = entity.toAggregate()
                val now = clock.instant()
                aggregate.review(command.actorId, command.decision, now)
                entity.apply(aggregate, now)
                requests.saveAndFlush(entity)
                decisions.saveAndFlush(
                    BreakGlassDecisionEntity(
                        identifiers.next(),
                        entity.id,
                        "POST_REVIEW",
                        command.actorId,
                        command.decision.name,
                        normalizedReason,
                        entity.version,
                        now,
                    ),
                )
                audits.appendAll(listOf(entity.audit("SUPPORT_BREAK_GLASS_REVIEWED", command.actorId, command.correlationId, now)))
                entity.toResource()
            }
        }

    private fun notification(
        requestId: UUID,
        eventType: String,
        now: Instant,
    ) {
        notifications.saveAndFlush(
            SecurityNotificationIntentEntity(
                identifiers.next(),
                requestId,
                eventType,
                "PENDING",
                0,
                now,
                null,
                now,
                now,
            ),
        )
    }

    private fun activeCase(caseId: UUID): SupportCaseEntity {
        val supportCase = cases.findLockedById(caseId) ?: notFound()
        if (supportCase.state !in ACTIVE_CASE_STATES) conflict("Terminal SupportCase rejects break-glass activation or reveal")
        return supportCase
    }

    private fun activeAssignedCase(
        caseId: UUID,
        actorId: UUID,
    ): SupportCaseEntity =
        activeCase(caseId).also {
            if (it.currentAssigneeId != actorId) throw DomainFailure(FailureCode.ACCESS_DENIED, "SupportCase assignment is required")
        }

    private fun activeLink(
        caseId: UUID,
        linkId: UUID,
    ): SupportCaseSubjectLinkEntity {
        val link = subjectLinks.findByIdAndSupportCaseId(linkId, caseId) ?: notFound()
        if (link.unlinkedAt != null) conflict("SupportCase subject link is inactive")
        return link
    }

    private fun SupportSubjectType.toBreakGlassSubjectType(): VerificationSubjectType =
        when (this) {
            SupportSubjectType.CUSTOMER -> VerificationSubjectType.CUSTOMER
            SupportSubjectType.STORE -> VerificationSubjectType.STORE
            SupportSubjectType.DELIVERY -> VerificationSubjectType.DELIVERY
            SupportSubjectType.ORDER -> invalid()
        }

    private fun processing(
        actorId: UUID,
        operation: String,
        key: String,
        payloadHash: String,
        resourceId: UUID,
        now: Instant,
    ): SupportSecurityIdempotencyEntity =
        SupportSecurityIdempotencyEntity(
            identifiers.next(),
            actorId,
            operation,
            key,
            payloadHash,
            resourceId,
            "PROCESSING",
            null,
            null,
            now,
            null,
            now.plus(IDEMPOTENCY_RETENTION),
        )

    private fun completeCommand(
        commandId: UUID,
        response: Any,
        now: Instant,
    ) {
        val entity = idempotency.findById(commandId).orElseThrow { IllegalStateException("Idempotency command is missing") }
        entity.complete(200, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(entity)
    }

    private fun replayOrExecute(
        actorId: UUID,
        operation: String,
        key: String,
        payloadHash: String,
        status: Int,
        execute: () -> BreakGlassResource,
    ): BreakGlassResource {
        idempotency.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)?.let { existing ->
            requirePayload(existing, payloadHash)
            if (existing.state != "COMPLETED" || existing.responseBody == null) {
                throw DomainFailure(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Break-glass command is in progress")
            }
            return objectMapper.readValue(existing.responseBody, BreakGlassResource::class.java)
        }
        val response = execute()
        val now = clock.instant()
        val command = processing(actorId, operation, key, payloadHash, response.requestId, now)
        command.complete(status, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(command)
        return response
    }

    private fun requirePayload(
        existing: SupportSecurityIdempotencyEntity,
        payloadHash: String,
    ) {
        if (existing.payloadHash != payloadHash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another break-glass command")
        }
    }

    private fun validateKey(value: String) {
        if (value != value.trim() || value.length !in 8..128 || value.any(Char::isISOControl)) invalid()
    }

    private fun <T> boundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Break-glass persistence is unavailable").also {
                it.initCause(failure)
            }
        } catch (failure: IllegalArgumentException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Break-glass separation or scope is invalid").also { it.initCause(failure) }
        } catch (failure: IllegalStateException) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Break-glass state does not allow this operation").also {
                it.initCause(failure)
            }
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Break-glass resource was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Break-glass request is invalid")

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val REQUEST_BREAK_GLASS = "REQUEST_BREAK_GLASS"
        const val DECIDE_BREAK_GLASS = "DECIDE_BREAK_GLASS"
        const val REVEAL_BREAK_GLASS = "REVEAL_BREAK_GLASS"
        const val REVIEW_BREAK_GLASS = "REVIEW_BREAK_GLASS"
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
    }
}

private fun BreakGlassRequest.toEntity(): BreakGlassRequestEntity =
    BreakGlassRequestEntity(
        id,
        caseId,
        subjectLinkId,
        subjectType,
        subjectId,
        requesterId,
        field,
        purpose,
        reasonCode,
        state,
        requestedAt,
        expiresAt,
        approverId,
        null,
        revealedAt,
        reviewerId,
        null,
        null,
        0,
    )

private fun BreakGlassRequestEntity.toAggregate(): BreakGlassRequest =
    BreakGlassRequest.restore(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        requesterId,
        field,
        purpose,
        reasonCode,
        requestedAt,
        state,
        expiresAt,
        approverId,
        revealedAt,
        reviewerId,
    )

private fun BreakGlassRequestEntity.apply(
    aggregate: BreakGlassRequest,
    now: Instant,
) {
    val previousState = state
    state = aggregate.state
    expiresAt = aggregate.expiresAt
    approverId = aggregate.approverId
    revealedAt = aggregate.revealedAt
    reviewerId = aggregate.reviewerId
    if (state == BreakGlassState.ACTIVE && approvedAt == null) approvedAt = now
    if (state == BreakGlassState.REVIEWED && reviewedAt == null) reviewedAt = now
    if (state == BreakGlassState.REVOKED && revokedAt == null) revokedAt = now
    if (previousState != state) version += 1
}

private fun BreakGlassRequestEntity.toResource(): BreakGlassResource =
    BreakGlassResource(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        field,
        purpose,
        reasonCode,
        state,
        requestedAt,
        expiresAt,
        version,
    )

private fun BreakGlassRequestEntity.audit(
    action: String,
    actorId: UUID,
    correlationId: String,
    occurredAt: Instant,
): AppendAuditRecordCommand =
    AppendAuditRecordCommand(
        actorId = actorId.toString(),
        actorType = AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.SECURITY_AND_PERMISSION,
        action = action,
        targetType = "SUPPORT_BREAK_GLASS_REQUEST",
        targetId = id,
        occurredAt = occurredAt,
        reason = reasonCode.name,
        afterSummary = mapOf("event" to action, "state" to state.name, "fieldCount" to "1", "postReview" to "REQUIRED"),
        correlationId = correlationId,
        sourceReference = "support-break-glass:$id:$action:$version",
    )
