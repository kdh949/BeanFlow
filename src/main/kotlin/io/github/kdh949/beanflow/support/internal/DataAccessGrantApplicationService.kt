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
import io.github.kdh949.beanflow.shared.api.PersonalDataField
import io.github.kdh949.beanflow.shared.api.RevealPersonalDataCommand
import io.github.kdh949.beanflow.shared.api.RevealedPersonalData
import io.github.kdh949.beanflow.support.internal.domain.ChallengeState
import io.github.kdh949.beanflow.support.internal.domain.DataAccessBinding
import io.github.kdh949.beanflow.support.internal.domain.DataAccessGrant
import io.github.kdh949.beanflow.support.internal.domain.DataAccessGrantState
import io.github.kdh949.beanflow.support.internal.domain.DataAccessReasonCode
import io.github.kdh949.beanflow.support.internal.domain.DataAccessRisk
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationSession
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
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

internal data class RequestDataAccessGrantCommand(
    val actorId: UUID,
    val caseId: UUID,
    val verificationSessionId: UUID,
    val purpose: VerificationPurpose,
    val fields: Set<SupportPersonalDataField>,
    val reasonCode: DataAccessReasonCode,
    val idempotencyKey: String,
    val correlationId: String,
)

internal enum class GrantDecision {
    APPROVE,
    DENY,
}

internal data class DecideDataAccessGrantCommand(
    val actorId: UUID,
    val grantId: UUID,
    val decision: GrantDecision,
    val expectedVersion: Long,
    val reasonCode: DataAccessReasonCode,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class RevealGrantedPersonalDataCommand(
    val actorId: UUID,
    val grantId: UUID,
    val fields: Set<SupportPersonalDataField>,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class DataAccessGrantResource(
    val grantId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType,
    val subjectId: UUID,
    val purpose: VerificationPurpose,
    val fields: Set<SupportPersonalDataField>,
    val risk: DataAccessRisk,
    val state: DataAccessGrantState,
    val maxReveals: Int,
    val reservedReveals: Int,
    val requestedAt: Instant,
    val expiresAt: Instant?,
    val version: Long,
)

internal class RevealedPersonalDataResource(
    val revealAttemptId: UUID,
    val grantId: UUID,
    val caseId: UUID,
    val subjectId: UUID,
    values: Map<SupportPersonalDataField, String>,
    val revealedAt: Instant,
) {
    val values: Map<SupportPersonalDataField, String> = values.toMap()

    override fun toString(): String =
        "RevealedPersonalDataResource(revealAttemptId=$revealAttemptId, grantId=$grantId, caseId=$caseId, " +
            "subjectId=$subjectId, fields=${values.keys}, values=<redacted>, revealedAt=$revealedAt)"
}

internal data class GrantRevealWork(
    val attemptId: UUID,
    val idempotencyId: UUID,
    val grantId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val actorId: UUID,
    val subjectType: io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType,
    val subjectId: UUID,
    val fields: Set<SupportPersonalDataField>,
    val risk: DataAccessRisk,
)

@Service
internal class DataAccessGrantApplicationService(
    private val transactions: DataAccessGrantTransactions,
    private val customers: CustomerSupportProfileRevealOperations,
    private val stores: StoreSupportProfileRevealOperations,
    private val couriers: ExternalCourierSupportProfileRevealOperations,
) {
    fun request(command: RequestDataAccessGrantCommand): DataAccessGrantResource = transactions.request(command)

    fun decide(command: DecideDataAccessGrantCommand): DataAccessGrantResource = transactions.decide(command)

    fun reveal(command: RevealGrantedPersonalDataCommand): RevealedPersonalDataResource {
        val work = transactions.reserveReveal(command)
        val ownerFields = work.fields.mapTo(linkedSetOf(), SupportPersonalDataField::toOwnerField)
        try {
            val revealed =
                when (work.subjectType) {
                    io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.CUSTOMER -> {
                        customers.reveal(RevealPersonalDataCommand(work.subjectId, ownerFields))
                    }

                    io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.STORE -> {
                        stores.reveal(RevealPersonalDataCommand(work.subjectId, ownerFields))
                    }

                    io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.DELIVERY -> {
                        couriers.reveal(RevealPersonalDataCommand(work.subjectId, ownerFields))
                    }
                }
            validateOwnerResponse(work, revealed)
            val values =
                work.fields.associateWith { field ->
                    revealed.values[field.toOwnerField()]
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Personal-data owner response is incomplete")
                }
            val completedAt = transactions.completeReveal(work)
            return RevealedPersonalDataResource(work.attemptId, work.grantId, work.caseId, work.subjectId, values, completedAt)
        } catch (failure: RuntimeException) {
            try {
                transactions.failReveal(work.attemptId, failure.failureClass())
            } catch (recordingFailure: RuntimeException) {
                failure.addSuppressed(recordingFailure)
            }
            if (failure is DomainFailure) throw failure
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Personal-data owner reveal is unavailable").also {
                it.initCause(failure)
            }
        }
    }

    private fun validateOwnerResponse(
        work: GrantRevealWork,
        response: RevealedPersonalData,
    ) {
        if (response.subjectId != work.subjectId ||
            response.values.keys != work.fields.mapTo(linkedSetOf(), SupportPersonalDataField::toOwnerField)
        ) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Personal-data owner response is invalid")
        }
    }

    private fun RuntimeException.failureClass(): String =
        if (this is DomainFailure && code != FailureCode.DEPENDENCY_UNAVAILABLE) "REVEAL_COMPLETION_REJECTED" else "OWNER_REVEAL_FAILED"
}

@Service
internal class DataAccessGrantTransactions(
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val challenges: VerificationChallengeJpaRepository,
    private val grants: DataAccessGrantJpaRepository,
    private val grantFields: DataAccessGrantFieldJpaRepository,
    private val decisions: DataAccessGrantDecisionJpaRepository,
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
    fun request(command: RequestDataAccessGrantCommand): DataAccessGrantResource =
        boundary {
            validateKey(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PII_REVEAL_REQUEST)
            commandLock.lock(command.caseId, command.actorId, REQUEST_GRANT, command.idempotencyKey)
            replayOrExecute(
                command.actorId,
                REQUEST_GRANT,
                command.idempotencyKey,
                hash(
                    "${command.caseId}|${command.verificationSessionId}|${command.purpose}|${command.fields.sortedBy {
                        it.name
                    }}|${command.reasonCode}",
                ),
                DataAccessGrantResource::class.java,
                201,
            ) {
                val supportCase = activeAssignedCase(command.caseId, command.actorId)
                val session = sessions.findLockedById(command.verificationSessionId) ?: verificationRequired()
                val link = activeLink(supportCase.id, session.subjectLinkId)
                if (session.actorId != command.actorId || session.purpose != command.purpose ||
                    session.supportCaseId != supportCase.id || session.subjectId != link.subjectId
                ) {
                    verificationRequired()
                }
                val sessionAggregate = session.toVerificationAggregate(verifiedChannels(session.id))
                val now = clock.instant()
                val required =
                    if (command.fields.any {
                            it.risk == DataAccessRisk.SENSITIVE
                        }
                    ) {
                        VerificationLevel.ENHANCED
                    } else {
                        VerificationLevel.BASIC
                    }
                if (!sessionAggregate.satisfies(required, now)) verificationRequired()
                val aggregate =
                    DataAccessGrant.request(
                        identifiers.next(),
                        supportCase.id,
                        link.id,
                        session.subjectType,
                        session.subjectId,
                        command.actorId,
                        command.purpose,
                        command.fields,
                        command.reasonCode,
                        now,
                    )
                permissions.requireActive(
                    command.actorId,
                    if (aggregate.risk == DataAccessRisk.BASIC) {
                        OperatorPermission.SUPPORT_PII_REVEAL_BASIC
                    } else {
                        OperatorPermission.SUPPORT_PII_REVEAL_SENSITIVE
                    },
                )
                aggregate.qualify(sessionAggregate.achievedLevel, now)
                val entity = aggregate.toEntity(session.id)
                grants.saveAndFlush(entity)
                grantFields.saveAllAndFlush(aggregate.fields.map { DataAccessGrantFieldEntity(entity.id, it) })
                audits.appendAll(
                    listOf(entity.securityAudit("SUPPORT_DATA_ACCESS_GRANT_REQUESTED", command.actorId, command.correlationId, now)),
                )
                entity.toResource(aggregate.fields)
            }
        }

    @Transactional
    fun decide(command: DecideDataAccessGrantCommand): DataAccessGrantResource =
        boundary {
            validateKey(command.idempotencyKey)
            if (command.reasonCode == DataAccessReasonCode.CONTACT_CONFIRMATION) invalid()
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PII_REVEAL_APPROVE)
            val caseId = grants.findCaseIdById(command.grantId) ?: notFound()
            commandLock.lock(caseId, command.actorId, DECIDE_GRANT, command.idempotencyKey)
            activeCase(caseId)
            replayOrExecute(
                command.actorId,
                DECIDE_GRANT,
                command.idempotencyKey,
                hash("${command.grantId}|${command.decision}|${command.expectedVersion}|${command.reasonCode}"),
                DataAccessGrantResource::class.java,
                200,
            ) {
                val entity = grants.findLockedById(command.grantId) ?: notFound()
                if (entity.supportCaseId != caseId) conflict("DataAccessGrant binding is stale")
                if (entity.version != command.expectedVersion) conflict("DataAccessGrant version is stale")
                val fields = fields(entity.id)
                val aggregate = entity.toAggregate(fields)
                val now = clock.instant()
                when (command.decision) {
                    GrantDecision.APPROVE -> aggregate.approve(command.actorId, now)
                    GrantDecision.DENY -> aggregate.deny(command.actorId)
                }
                entity.apply(aggregate, now)
                grants.saveAndFlush(entity)
                decisions.saveAndFlush(
                    DataAccessGrantDecisionEntity(
                        identifiers.next(),
                        entity.id,
                        command.actorId,
                        if (command.decision == GrantDecision.APPROVE) "APPROVED" else "DENIED",
                        command.reasonCode,
                        entity.version,
                        now,
                    ),
                )
                audits.appendAll(
                    listOf(entity.securityAudit("SUPPORT_DATA_ACCESS_GRANT_DECIDED", command.actorId, command.correlationId, now)),
                )
                entity.toResource(fields)
            }
        }

    @Transactional
    fun reserveReveal(command: RevealGrantedPersonalDataCommand): GrantRevealWork =
        boundary {
            validateKey(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_PII_REVEAL_REQUEST)
            val caseId = grants.findCaseIdById(command.grantId) ?: notFound()
            commandLock.lock(caseId, command.actorId, REVEAL_GRANT, command.idempotencyKey)
            val supportCase = activeAssignedCase(caseId, command.actorId)
            val payloadHash = hash("${command.grantId}|${command.fields.sortedBy { it.name }}")
            idempotency.findByActorIdAndOperationAndIdempotencyKey(command.actorId, REVEAL_GRANT, command.idempotencyKey)?.let {
                requirePayload(it, payloadHash)
                throw DomainFailure(
                    FailureCode.IDEMPOTENCY_MANUAL_REVIEW_REQUIRED,
                    "Raw personal-data reveal responses are not persisted or replayed",
                )
            }
            val entity = grants.findLockedById(command.grantId) ?: notFound()
            if (entity.supportCaseId != caseId) conflict("DataAccessGrant binding is stale")
            if (entity.requesterId !=
                command.actorId
            ) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "DataAccessGrant belongs to another operator")
            }
            val link = activeLink(supportCase.id, entity.subjectLinkId)
            if (link.subjectType.toVerificationSubjectType() != entity.subjectType || link.subjectId != entity.subjectId) {
                conflict("DataAccessGrant subject binding is stale")
            }
            permissions.requireActive(
                command.actorId,
                if (entity.risk == DataAccessRisk.BASIC) {
                    OperatorPermission.SUPPORT_PII_REVEAL_BASIC
                } else {
                    OperatorPermission.SUPPORT_PII_REVEAL_SENSITIVE
                },
            )
            val fields = fields(entity.id)
            val aggregate = entity.toAggregate(fields)
            val now = clock.instant()
            aggregate.reserveReveal(
                command.fields,
                DataAccessBinding(supportCase.id, entity.subjectLinkId, entity.subjectId, entity.purpose),
                now,
            )
            entity.apply(aggregate, now)
            grants.saveAndFlush(entity)
            val attemptId = identifiers.next()
            revealAttempts.saveAndFlush(
                RevealAttemptEntity(
                    attemptId,
                    "GRANT",
                    entity.id,
                    null,
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
            revealFields.saveAllAndFlush(command.fields.map { RevealAttemptFieldEntity(attemptId, it) })
            val idempotencyEntity = processing(command.actorId, REVEAL_GRANT, command.idempotencyKey, payloadHash, attemptId, now)
            idempotency.saveAndFlush(idempotencyEntity)
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
                                "accessPath" to "GRANT",
                                "fieldCount" to command.fields.size.toString(),
                                "risk" to entity.risk.name,
                            ),
                        correlationId = command.correlationId,
                        sourceReference = "support-reveal-attempt:$attemptId",
                    ),
                ),
            )
            GrantRevealWork(
                attemptId,
                idempotencyEntity.id,
                entity.id,
                supportCase.id,
                entity.subjectLinkId,
                command.actorId,
                entity.subjectType,
                entity.subjectId,
                command.fields,
                entity.risk,
            )
        }

    @Transactional
    fun completeReveal(work: GrantRevealWork): Instant =
        boundary {
            activeAssignedCase(work.caseId, work.actorId)
            val link = activeLink(work.caseId, work.subjectLinkId)
            if (link.subjectType.toVerificationSubjectType() != work.subjectType || link.subjectId != work.subjectId) {
                conflict("DataAccessGrant subject binding is stale")
            }
            permissions.requireActive(work.actorId, OperatorPermission.SUPPORT_PII_REVEAL_REQUEST)
            permissions.requireActive(
                work.actorId,
                if (work.risk == DataAccessRisk.BASIC) {
                    OperatorPermission.SUPPORT_PII_REVEAL_BASIC
                } else {
                    OperatorPermission.SUPPORT_PII_REVEAL_SENSITIVE
                },
            )
            val attempt = revealAttempts.findLockedById(work.attemptId) ?: notFound()
            if (attempt.actorId != work.actorId || attempt.supportCaseId != work.caseId) conflict("RevealAttempt binding is stale")
            val now = clock.instant()
            attempt.revealed(now)
            revealAttempts.saveAndFlush(attempt)
            val receipt = mapOf("revealAttemptId" to work.attemptId.toString(), "state" to "REVEALED")
            completeCommand(work.idempotencyId, receipt, now)
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

    private fun fields(grantId: UUID): Set<SupportPersonalDataField> =
        grantFields.findByGrantIdOrderByFieldAsc(grantId).mapTo(linkedSetOf(), DataAccessGrantFieldEntity::field)

    private fun verifiedChannels(sessionId: UUID): Set<VerificationChannel> =
        challenges.findDistinctChannelsBySessionIdAndState(sessionId, ChallengeState.VERIFIED)

    private fun activeCase(caseId: UUID): SupportCaseEntity {
        val supportCase = cases.findLockedById(caseId) ?: notFound()
        if (supportCase.state !in ACTIVE_CASE_STATES) conflict("Terminal SupportCase rejects DataAccessGrant")
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

    private fun SupportSubjectType.toVerificationSubjectType(): io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType =
        when (this) {
            SupportSubjectType.CUSTOMER -> io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.CUSTOMER
            SupportSubjectType.STORE -> io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.STORE
            SupportSubjectType.DELIVERY -> io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType.DELIVERY
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

    private fun <T : Any> replayOrExecute(
        actorId: UUID,
        operation: String,
        key: String,
        payloadHash: String,
        type: Class<T>,
        status: Int,
        execute: () -> T,
    ): T {
        idempotency.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)?.let { existing ->
            requirePayload(existing, payloadHash)
            if (existing.state != "COMPLETED" || existing.responseBody == null) {
                throw DomainFailure(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "DataAccessGrant command is in progress")
            }
            return objectMapper.readValue(existing.responseBody, type)
        }
        val response = execute()
        val now = clock.instant()
        val command = processing(actorId, operation, key, payloadHash, response.grantId(), now)
        command.complete(status, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(command)
        return response
    }

    private fun Any.grantId(): UUID = (this as DataAccessGrantResource).grantId

    private fun requirePayload(
        existing: SupportSecurityIdempotencyEntity,
        payloadHash: String,
    ) {
        if (existing.payloadHash != payloadHash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another DataAccessGrant command")
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
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "DataAccessGrant persistence is unavailable").also {
                it.initCause(failure)
            }
        } catch (failure: IllegalArgumentException) {
            throw DomainFailure(FailureCode.DATA_ACCESS_SCOPE_MISMATCH, "DataAccessGrant scope is invalid").also {
                it.initCause(failure)
            }
        } catch (failure: IllegalStateException) {
            throw DomainFailure(FailureCode.DATA_ACCESS_GRANT_REQUIRED, "An active matching DataAccessGrant is required").also {
                it.initCause(failure)
            }
        }

    private fun verificationRequired(): Nothing =
        throw DomainFailure(FailureCode.VERIFICATION_REQUIRED, "Matching verification is required")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "DataAccessGrant resource was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "DataAccessGrant request is invalid")

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val REQUEST_GRANT = "REQUEST_GRANT"
        const val DECIDE_GRANT = "DECIDE_GRANT"
        const val REVEAL_GRANT = "REVEAL_GRANT"
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
    }
}

private fun VerificationSessionEntity.toVerificationAggregate(channels: Set<VerificationChannel>): VerificationSession =
    VerificationSession.restore(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        actorId,
        purpose,
        VerificationActionScope.PERSONAL_DATA_REVEAL,
        requestedLevel,
        startedAt,
        expiresAt,
        state,
        invalidAttempts,
        channels,
    )

private fun DataAccessGrant.toEntity(sessionId: UUID): DataAccessGrantEntity =
    DataAccessGrantEntity(
        id,
        caseId,
        subjectLinkId,
        subjectType,
        subjectId,
        requesterId,
        sessionId,
        purpose,
        reasonCode,
        risk,
        state,
        maxReveals,
        reservedReveals,
        requestedAt,
        expiresAt,
        approverId,
        null,
        null,
        0,
    )

private fun DataAccessGrantEntity.toAggregate(fields: Set<SupportPersonalDataField>): DataAccessGrant =
    DataAccessGrant.restore(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        requesterId,
        purpose,
        fields,
        reasonCode,
        requestedAt,
        state,
        expiresAt,
        reservedReveals,
        approverId,
    )

private fun DataAccessGrantEntity.apply(
    aggregate: DataAccessGrant,
    now: Instant,
) {
    val previousState = state
    val previousReserved = reservedReveals
    state = aggregate.state
    reservedReveals = aggregate.reservedReveals
    expiresAt = aggregate.expiresAt
    approverId = aggregate.approverId
    if (aggregate.approverId != null && decidedAt == null) decidedAt = now
    if (state == DataAccessGrantState.REVOKED && revokedAt == null) revokedAt = now
    if (previousState != state || previousReserved != reservedReveals) version += 1
}

private fun DataAccessGrantEntity.toResource(fields: Set<SupportPersonalDataField>): DataAccessGrantResource =
    DataAccessGrantResource(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        purpose,
        fields,
        risk,
        state,
        maxReveals,
        reservedReveals,
        requestedAt,
        expiresAt,
        version,
    )

private fun DataAccessGrantEntity.securityAudit(
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
        targetType = "SUPPORT_DATA_ACCESS_GRANT",
        targetId = id,
        occurredAt = occurredAt,
        reason = reasonCode.name,
        afterSummary = mapOf("event" to action, "state" to state.name, "risk" to risk.name, "fieldCount" to "BOUNDED"),
        correlationId = correlationId,
        sourceReference = "support-data-access-grant:$id:$action:$version",
    )

internal fun SupportPersonalDataField.toOwnerField(): PersonalDataField =
    when (this) {
        SupportPersonalDataField.CUSTOMER_DISPLAY_NAME,
        SupportPersonalDataField.COURIER_DISPLAY_NAME,
        -> PersonalDataField.DISPLAY_NAME

        SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE -> PersonalDataField.PRIMARY_PHONE

        SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL -> PersonalDataField.PRIMARY_EMAIL

        SupportPersonalDataField.STORE_LEGAL_DISPLAY_NAME -> PersonalDataField.LEGAL_DISPLAY_NAME

        SupportPersonalDataField.STORE_SUPPORT_PHONE -> PersonalDataField.SUPPORT_PHONE

        SupportPersonalDataField.STORE_SUPPORT_EMAIL -> PersonalDataField.SUPPORT_EMAIL

        SupportPersonalDataField.COURIER_PROVIDER_REFERENCE -> PersonalDataField.PROVIDER_COURIER_REFERENCE

        SupportPersonalDataField.COURIER_RELAY_PHONE -> PersonalDataField.RELAY_PHONE

        SupportPersonalDataField.COURIER_RELAY_EMAIL -> PersonalDataField.RELAY_EMAIL
    }
