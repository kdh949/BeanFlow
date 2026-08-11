package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OpenOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationDecision
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationOperations
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationReturnHandler
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationSnapshot
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationState
import io.github.kdh949.beanflow.operations.api.OperationsSupportReturnState
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.ReturnOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class DecideOperationsSupportInvestigationCommand(
    val actorId: UUID,
    val investigationId: UUID,
    val expectedVersion: Long,
    val decision: OperationsSupportInvestigationDecision,
    val reason: String,
    val evidenceDigest: String,
    val idempotencyKey: String,
    val now: Instant,
)

internal data class OperationsSupportInvestigationDecisionResource(
    val investigationId: UUID,
    val requestId: UUID,
    val revisionId: UUID,
    val revisionNumber: Int,
    val state: OperationsSupportInvestigationState,
    val supportRequestState: String,
    val supportRequestVersion: Long,
    val decidedByActorId: UUID?,
    val decidedAt: Instant?,
    val version: Long,
)

internal sealed interface OperationsSupportInvestigationOutcome {
    data class Succeeded(
        val resource: OperationsSupportInvestigationDecisionResource,
    ) : OperationsSupportInvestigationOutcome

    data class Failed(
        val resource: OperationsSupportInvestigationDecisionResource,
        val code: FailureCode,
        val message: String,
    ) : OperationsSupportInvestigationOutcome
}

@Service
internal class OperationsSupportInvestigationService(
    private val investigations: OperationsSupportInvestigationJpaRepository,
    private val idempotencies: OperationsSupportInvestigationIdempotencyJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
    private val returnHandler: OperationsSupportInvestigationReturnHandler,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
) : OperationsSupportInvestigationOperations {
    @Transactional
    override fun open(command: OpenOperationsSupportInvestigationCommand): OperationsSupportInvestigationSnapshot {
        validateOpen(command)
        advisoryLock.lock("operations-support-investigation:${command.supportActionRequestId}:${command.revisionNumber}")
        investigations
            .findBySupportActionRequestIdAndRevisionNumber(
                command.supportActionRequestId,
                command.revisionNumber,
            )?.let { existing ->
                if (existing.supportActionRevisionId != command.supportActionRevisionId ||
                    existing.requesterActorId != command.requesterActorId ||
                    existing.supportApproverActorId != command.supportApproverActorId ||
                    existing.executorActorId != command.executorActorId ||
                    existing.expiresAt != command.expiresAt
                ) {
                    stale("Investigation source binding conflicts with an existing case")
                }
                return existing.toSnapshot()
            }
        val entity =
            OperationsSupportInvestigationEntity(
                identifiers.next(),
                command.supportActionRequestId,
                command.supportActionRevisionId,
                command.revisionNumber,
                command.requesterActorId,
                command.supportApproverActorId,
                command.executorActorId,
                OperationsSupportInvestigationState.OPEN,
                command.openedAt,
                command.expiresAt,
                null,
                null,
                null,
                null,
                command.openedAt,
                0,
            )
        investigations.saveAndFlush(entity)
        audits.appendAll(
            listOf(
                entity.audit(
                    command.requesterActorId.toString(),
                    AuditActorType.PLATFORM_OPERATOR,
                    "OPERATIONS_SUPPORT_INVESTIGATION_OPENED",
                    "INVESTIGATION_OPENED",
                    null,
                    entity.state,
                    command.openedAt,
                ),
            ),
        )
        return entity.toSnapshot()
    }

    @Transactional
    fun decide(command: DecideOperationsSupportInvestigationCommand): OperationsSupportInvestigationOutcome {
        val normalized = command.normalized()
        permissions.requireActive(normalized.actorId, OperatorPermission.OPERATIONS_SUPPORT_INVESTIGATION)
        advisoryLock.lock("operations-support-investigation:idempotency:${normalized.actorId}:${normalized.idempotencyKey}")
        val payloadHash = normalized.payloadHash()
        replay(normalized.actorId, normalized.idempotencyKey, payloadHash)?.let { return it }
        val entity = investigations.findLockedById(normalized.investigationId) ?: notFound()
        if (entity.version != normalized.expectedVersion) stale("Operations investigation version is stale")
        if (entity.state != OperationsSupportInvestigationState.OPEN) conflict("Operations investigation is already terminal")
        requireSeparatedReviewer(entity, normalized.actorId)

        if (!normalized.now.isBefore(entity.expiresAt)) {
            val returned =
                returnHandler.returnDecision(
                    entity.returnCommand(null, null, OperationsSupportReturnState.EXPIRED, normalized.now),
                )
            terminalizeSystem(entity, OperationsSupportInvestigationState.EXPIRED, "INVESTIGATION_EXPIRED", normalized.now)
            val response = entity.toResource(returned.supportRequestState, returned.supportRequestVersion)
            saveIdempotency(normalized, payloadHash, response, FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED)
            return OperationsSupportInvestigationOutcome.Failed(
                response,
                FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED,
                "Operations investigation has expired",
            )
        }

        val returned =
            returnHandler.returnDecision(
                entity.returnCommand(normalized.actorId, normalized.decision, OperationsSupportReturnState.APPLIED, normalized.now),
            )
        if (returned.state != OperationsSupportReturnState.APPLIED) {
            val state =
                when (returned.state) {
                    OperationsSupportReturnState.STALE -> OperationsSupportInvestigationState.STALE
                    OperationsSupportReturnState.EXPIRED -> OperationsSupportInvestigationState.EXPIRED
                    OperationsSupportReturnState.APPLIED -> error("Applied return cannot enter invalid branch")
                }
            terminalizeSystem(entity, state, "SUPPORT_BINDING_${returned.state.name}", normalized.now)
            val response = entity.toResource(returned.supportRequestState, returned.supportRequestVersion)
            val code =
                if (returned.state == OperationsSupportReturnState.EXPIRED) {
                    FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED
                } else {
                    FailureCode.SUPPORT_ACTION_REQUEST_STALE
                }
            saveIdempotency(normalized, payloadHash, response, code)
            return OperationsSupportInvestigationOutcome.Failed(
                response,
                code,
                "Support approval binding is ${returned.state.name.lowercase()}",
            )
        }

        val previous = entity.state
        entity.state = normalized.decision.toState()
        entity.decidedByActorId = normalized.actorId
        entity.decisionReason = normalized.reason
        entity.decisionEvidenceDigest = normalized.evidenceDigest
        entity.decidedAt = normalized.now
        entity.updatedAt = normalized.now
        entity.version += 1
        investigations.saveAndFlush(entity)
        audits.appendAll(
            listOf(
                entity.audit(
                    normalized.actorId.toString(),
                    AuditActorType.PLATFORM_OPERATOR,
                    "OPERATIONS_SUPPORT_INVESTIGATION_DECIDED",
                    normalized.decision.name,
                    previous,
                    entity.state,
                    normalized.now,
                ),
            ),
        )
        val response = entity.toResource(returned.supportRequestState, returned.supportRequestVersion)
        saveIdempotency(normalized, payloadHash, response, null)
        return OperationsSupportInvestigationOutcome.Succeeded(response)
    }

    private fun terminalizeSystem(
        entity: OperationsSupportInvestigationEntity,
        state: OperationsSupportInvestigationState,
        reason: String,
        now: Instant,
    ) {
        val previous = entity.state
        entity.state = state
        entity.decidedByActorId = null
        entity.decisionReason = reason
        entity.decisionEvidenceDigest = null
        entity.decidedAt = now
        entity.updatedAt = now
        entity.version += 1
        investigations.saveAndFlush(entity)
        audits.appendAll(
            listOf(
                entity.audit(
                    "SYSTEM",
                    AuditActorType.SYSTEM,
                    "OPERATIONS_SUPPORT_INVESTIGATION_DECIDED",
                    reason,
                    previous,
                    entity.state,
                    now,
                ),
            ),
        )
    }

    private fun requireSeparatedReviewer(
        entity: OperationsSupportInvestigationEntity,
        actorId: UUID,
    ) {
        if (actorId == entity.requesterActorId || actorId == entity.executorActorId || actorId == entity.supportApproverActorId) {
            throw DomainFailure(
                FailureCode.SUPPORT_APPROVER_MUST_DIFFER,
                "Operations reviewer must differ from requester, Support approver and executor",
            )
        }
    }

    private fun replay(
        actorId: UUID,
        key: String,
        hash: String,
    ): OperationsSupportInvestigationOutcome? {
        val existing =
            idempotencies.findByActorIdAndOperationAndIdempotencyKey(
                actorId,
                OperationsSupportInvestigationIdempotencyOperation.DECIDE,
                key,
            ) ?: return null
        if (existing.payloadHash != hash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another investigation decision")
        }
        val response = objectMapper.readValue(existing.responseBody, OperationsSupportInvestigationDecisionResource::class.java)
        val failure = existing.failureCode?.let(FailureCode::valueOf)
        return if (failure == null) {
            OperationsSupportInvestigationOutcome.Succeeded(response)
        } else {
            OperationsSupportInvestigationOutcome.Failed(response, failure, failureMessage(failure))
        }
    }

    private fun saveIdempotency(
        command: DecideOperationsSupportInvestigationCommand,
        hash: String,
        response: OperationsSupportInvestigationDecisionResource,
        failure: FailureCode?,
    ) {
        idempotencies.saveAndFlush(
            OperationsSupportInvestigationIdempotencyEntity(
                identifiers.next(),
                command.actorId,
                OperationsSupportInvestigationIdempotencyOperation.DECIDE,
                command.idempotencyKey,
                hash,
                command.investigationId,
                if (failure == null) 200 else 409,
                objectMapper.writeValueAsString(response),
                failure?.name,
                command.now,
                command.now.plus(IDEMPOTENCY_RETENTION),
            ),
        )
    }

    private fun DecideOperationsSupportInvestigationCommand.normalized(): DecideOperationsSupportInvestigationCommand =
        copy(
            idempotencyKey = idempotencyKey.trim(),
            reason = reason.trim(),
            evidenceDigest = evidenceDigest.trim(),
        ).also {
            if (it.expectedVersion < 0 || it.idempotencyKey.length !in 8..128 || it.idempotencyKey.any(Char::isISOControl) ||
                it.reason.length !in 1..500 || it.reason.any(Char::isISOControl) || !it.evidenceDigest.matches(SHA_256)
            ) {
                invalid()
            }
        }

    private fun validateOpen(command: OpenOperationsSupportInvestigationCommand) {
        if (command.revisionNumber < 1 || !command.openedAt.isBefore(command.expiresAt) ||
            command.supportApproverActorId == command.requesterActorId ||
            command.supportApproverActorId == command.executorActorId
        ) {
            invalid()
        }
    }

    private fun DecideOperationsSupportInvestigationCommand.payloadHash(): String =
        sha256(
            listOf(
                actorId,
                investigationId,
                expectedVersion,
                decision,
                reason,
                evidenceDigest,
            ).joinToString("\u0000"),
        )

    private fun OperationsSupportInvestigationEntity.returnCommand(
        actorId: UUID?,
        decision: OperationsSupportInvestigationDecision?,
        terminalState: OperationsSupportReturnState,
        now: Instant,
    ) = ReturnOperationsSupportInvestigationCommand(
        supportActionRequestId,
        supportActionRevisionId,
        revisionNumber,
        actorId,
        decision,
        terminalState,
        now,
    )

    private fun OperationsSupportInvestigationEntity.toSnapshot() =
        OperationsSupportInvestigationSnapshot(
            id,
            supportActionRequestId,
            supportActionRevisionId,
            revisionNumber,
            state,
            openedAt,
            expiresAt,
            decidedByActorId,
            decidedAt,
            version,
        )

    private fun OperationsSupportInvestigationEntity.toResource(
        supportState: String,
        supportVersion: Long,
    ) = OperationsSupportInvestigationDecisionResource(
        id,
        supportActionRequestId,
        supportActionRevisionId,
        revisionNumber,
        state,
        supportState,
        supportVersion,
        decidedByActorId,
        decidedAt,
        version,
    )

    private fun OperationsSupportInvestigationEntity.audit(
        actorId: String,
        actorType: AuditActorType,
        action: String,
        outcome: String,
        before: OperationsSupportInvestigationState?,
        after: OperationsSupportInvestigationState,
        now: Instant,
    ) = AppendAuditRecordCommand(
        actorId,
        actorType,
        AuditCategory.OPERATIONS_POLICY,
        action,
        "OPERATIONS_SUPPORT_INVESTIGATION",
        id,
        now,
        "SUPPORT_INVESTIGATION",
        before?.let { mapOf("state" to it.name) } ?: emptyMap(),
        mapOf("outcome" to outcome, "state" to after.name, "revision" to revisionNumber.toString()),
        correlations.currentOrCreate(),
        "operations-support-investigation:$id:$action:$version",
    )

    private fun OperationsSupportInvestigationDecision.toState(): OperationsSupportInvestigationState =
        when (this) {
            OperationsSupportInvestigationDecision.APPROVE -> OperationsSupportInvestigationState.APPROVED
            OperationsSupportInvestigationDecision.DENY -> OperationsSupportInvestigationState.DENIED
            OperationsSupportInvestigationDecision.RETURN_FOR_REVISION -> OperationsSupportInvestigationState.RETURNED
            OperationsSupportInvestigationDecision.ESCALATE -> OperationsSupportInvestigationState.ESCALATED
        }

    private fun failureMessage(code: FailureCode): String =
        when (code) {
            FailureCode.SUPPORT_ACTION_REQUEST_EXPIRED -> "Operations investigation has expired"
            FailureCode.SUPPORT_ACTION_REQUEST_STALE -> "Support approval binding is stale"
            else -> "Operations investigation decision failed"
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Operations investigation request is invalid")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Operations investigation was not found")

    private fun stale(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, message)

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.SUPPORT_INVESTIGATION_STATE_CONFLICT, message)

    private companion object {
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}
