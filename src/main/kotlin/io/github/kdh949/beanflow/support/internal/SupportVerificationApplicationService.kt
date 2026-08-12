package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.identity.api.IssueVerificationChallengeCommand
import io.github.kdh949.beanflow.identity.api.RegisteredVerificationChannel
import io.github.kdh949.beanflow.identity.api.SensitiveVerificationProof
import io.github.kdh949.beanflow.identity.api.VerificationChallengeIssueResult
import io.github.kdh949.beanflow.identity.api.VerificationChallengeOperations
import io.github.kdh949.beanflow.identity.api.VerificationChallengeVerifyResult
import io.github.kdh949.beanflow.identity.api.VerifyChallengeCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.ChallengeOutcome
import io.github.kdh949.beanflow.support.internal.domain.ChallengeState
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationChallenge
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationSession
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import org.springframework.beans.factory.ObjectProvider
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
import kotlin.math.ceil

internal data class CreateVerificationSessionCommand(
    val actorId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val requestedLevel: VerificationLevel,
    val purpose: VerificationPurpose,
    val actionScope: VerificationActionScope,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class IssueVerificationChallengeRequestCommand(
    val actorId: UUID,
    val sessionId: UUID,
    val channel: VerificationChannel,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class VerifySupportChallengeCommand(
    val actorId: UUID,
    val challengeId: UUID,
    val proof: SensitiveVerificationProof,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class RevokeVerificationSessionCommand(
    val actorId: UUID,
    val sessionId: UUID,
    val idempotencyKey: String,
    val correlationId: String,
)

internal data class VerificationSessionResource(
    val sessionId: UUID,
    val caseId: UUID,
    val subjectLinkId: UUID,
    val subjectType: VerificationSubjectType,
    val subjectId: UUID,
    val purpose: VerificationPurpose,
    val actionScope: VerificationActionScope,
    val requestedLevel: VerificationLevel,
    val achievedLevel: VerificationLevel,
    val state: VerificationState,
    val invalidAttempts: Int,
    val startedAt: Instant,
    val expiresAt: Instant,
    val version: Long,
    val challenges: List<VerificationChallengeResource>,
)

internal data class VerificationChallengeResource(
    val challengeId: UUID,
    val sessionId: UUID,
    val channel: VerificationChannel,
    val state: ChallengeState,
    val requestedAt: Instant,
    val expiresAt: Instant,
)

internal data class VerificationResultResource(
    val challenge: VerificationChallengeResource,
    val sessionState: VerificationState,
    val achievedLevel: VerificationLevel,
    val invalidAttempts: Int,
    val lockedUntil: Instant?,
)

@Service
internal class SupportVerificationApplicationService(
    private val transactions: SupportVerificationTransactions,
    private val challengeProviders: ObjectProvider<VerificationChallengeOperations>,
) {
    fun create(command: CreateVerificationSessionCommand): VerificationSessionResource = transactions.create(command)

    fun get(
        actorId: UUID,
        sessionId: UUID,
    ): VerificationSessionResource = transactions.get(actorId, sessionId)

    fun issue(command: IssueVerificationChallengeRequestCommand): VerificationChallengeResource {
        val provider = provider()
        return when (val start = transactions.beginIssue(command)) {
            is ChallengeIssueStart.Replay -> {
                start.response
            }

            is ChallengeIssueStart.Work -> {
                val result =
                    try {
                        provider.issue(
                            IssueVerificationChallengeCommand(
                                challengeIntentId = start.challengeId,
                                subjectType = start.subjectType.name,
                                subjectId = start.subjectId,
                                channel = RegisteredVerificationChannel.valueOf(start.channel.name),
                            ),
                        )
                    } catch (_: RuntimeException) {
                        VerificationChallengeIssueResult.Unknown("PROVIDER_OUTCOME_UNKNOWN")
                    }
                transactions.completeIssue(start, result)
            }
        }
    }

    fun verify(command: VerifySupportChallengeCommand): VerificationResultResource {
        val provider = provider()
        command.proof.use { proof ->
            return when (val start = transactions.beginVerify(command)) {
                is ChallengeVerifyStart.Replay -> {
                    start.response
                }

                is ChallengeVerifyStart.Work -> {
                    val result =
                        try {
                            provider.verify(
                                VerifyChallengeCommand(
                                    challengeIntentId = start.challengeId,
                                    opaqueProviderReference = start.opaqueProviderReference,
                                    proof = proof,
                                ),
                            )
                        } catch (_: RuntimeException) {
                            VerificationChallengeVerifyResult.UNKNOWN
                        }
                    transactions.completeVerify(start, result)
                }
            }
        }
    }

    fun revoke(command: RevokeVerificationSessionCommand): VerificationSessionResource = transactions.revoke(command)

    private fun provider(): VerificationChallengeOperations =
        challengeProviders.getIfUnique()
            ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Verification challenge provider is unavailable")
}

internal sealed interface ChallengeIssueStart {
    data class Replay(
        val response: VerificationChallengeResource,
    ) : ChallengeIssueStart

    data class Work(
        val actorId: UUID,
        val challengeId: UUID,
        val sessionId: UUID,
        val caseId: UUID,
        val subjectType: VerificationSubjectType,
        val subjectId: UUID,
        val channel: VerificationChannel,
        val idempotencyId: UUID,
        val correlationId: String,
    ) : ChallengeIssueStart
}

internal sealed interface ChallengeVerifyStart {
    data class Replay(
        val response: VerificationResultResource,
    ) : ChallengeVerifyStart

    data class Work(
        val actorId: UUID,
        val challengeId: UUID,
        val sessionId: UUID,
        val caseId: UUID,
        val opaqueProviderReference: String,
        val idempotencyId: UUID,
        val correlationId: String,
    ) : ChallengeVerifyStart
}

@Service
internal class SupportVerificationTransactions(
    private val cases: SupportCaseJpaRepository,
    private val subjectLinks: SupportCaseSubjectLinkJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val challenges: VerificationChallengeJpaRepository,
    private val attempts: VerificationAttemptJpaRepository,
    private val lockouts: VerificationLockoutJpaRepository,
    private val idempotency: SupportSecurityIdempotencyJpaRepository,
    private val commandLock: SupportCaseCommandLock,
    private val permissions: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateVerificationSessionCommand): VerificationSessionResource =
        boundary {
            validateIdempotency(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            commandLock.lock(command.caseId, command.actorId, CREATE_SESSION, command.idempotencyKey)
            replayOrExecute(
                command.actorId,
                CREATE_SESSION,
                command.idempotencyKey,
                hash(
                    listOf(
                        command.caseId,
                        command.subjectLinkId,
                        command.requestedLevel,
                        command.purpose,
                        command.actionScope,
                    ).joinToString("|"),
                ),
                VerificationSessionResource::class.java,
                201,
            ) {
                val now = clock.instant()
                val supportCase = activeAssignedCase(command.caseId, command.actorId)
                val link = activeLink(supportCase.id, command.subjectLinkId)
                val subjectType = link.subjectType.toVerificationSubjectType()
                lockouts.findLocked(supportCase.id, subjectType, link.subjectId)?.let { lockout ->
                    if (now.isBefore(lockout.lockedUntil)) {
                        val retry = ceil(Duration.between(now, lockout.lockedUntil).toMillis() / 1000.0).toLong().coerceAtLeast(1)
                        throw DomainFailure(FailureCode.VERIFICATION_LOCKED, "Verification binding is locked", retry)
                    }
                    lockouts.delete(lockout)
                }
                val aggregate =
                    VerificationSession.start(
                        id = identifiers.next(),
                        caseId = supportCase.id,
                        subjectLinkId = link.id,
                        subjectType = subjectType,
                        subjectId = link.subjectId,
                        actorId = command.actorId,
                        purpose = command.purpose,
                        actionScope = command.actionScope,
                        requestedLevel = command.requestedLevel,
                        startedAt = now,
                    )
                val entity = aggregate.toEntity()
                sessions.saveAndFlush(entity)
                audits.appendAll(listOf(entity.audit("SUPPORT_VERIFICATION_SESSION_CREATED", command.actorId, command.correlationId, now)))
                entity.toResource(emptyList())
            }
        }

    @Transactional
    fun get(
        actorId: UUID,
        sessionId: UUID,
    ): VerificationSessionResource =
        boundary {
            permissions.requireActive(actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            val caseId = sessions.findCaseIdById(sessionId) ?: notFound()
            activeAssignedCase(caseId, actorId)
            val entity = sessions.findLockedById(sessionId) ?: notFound()
            requireSessionOwner(entity, actorId)
            val aggregate = entity.toAggregate(verifiedChannels(entity.id))
            val now = clock.instant()
            aggregate.refresh(now)
            entity.apply(aggregate, now)
            sessions.saveAndFlush(entity)
            val challengeEntities = challenges.findLockedBySessionIdOrderByRequestedAtAscIdAsc(entity.id)
            challengeEntities.forEach { challenge ->
                val challengeAggregate = challenge.toAggregate()
                val previousState = challengeAggregate.state
                challengeAggregate.refresh(now)
                if (challengeAggregate.state != previousState) challenge.apply(challengeAggregate, now)
            }
            challenges.saveAllAndFlush(challengeEntities)
            entity.toResource(challengeEntities)
        }

    @Transactional
    fun beginIssue(command: IssueVerificationChallengeRequestCommand): ChallengeIssueStart =
        boundary {
            validateIdempotency(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            val caseId = sessions.findCaseIdById(command.sessionId) ?: notFound()
            commandLock.lock(caseId, command.actorId, ISSUE_CHALLENGE, command.idempotencyKey)
            activeAssignedCase(caseId, command.actorId)
            val session = sessions.findLockedById(command.sessionId) ?: notFound()
            requireSessionOwner(session, command.actorId)
            activeLink(caseId, session.subjectLinkId)
            existingCommand(command.actorId, ISSUE_CHALLENGE, command.idempotencyKey)?.let { existing ->
                requirePayload(existing, hash("${command.sessionId}|${command.channel}"))
                return@boundary ChallengeIssueStart.Replay(readCompleted(existing, VerificationChallengeResource::class.java))
            }
            val aggregate = session.toAggregate(verifiedChannels(session.id))
            aggregate.refresh(clock.instant())
            check(aggregate.state == VerificationState.PENDING) { "Verification session is not pending" }
            session.apply(aggregate, clock.instant())
            sessions.saveAndFlush(session)
            val now = clock.instant()
            val challenge = VerificationChallenge.request(identifiers.next(), session.id, command.channel, now)
            val entity = challenge.toEntity()
            challenges.saveAndFlush(entity)
            val idempotencyEntity =
                processingCommand(
                    command.actorId,
                    ISSUE_CHALLENGE,
                    command.idempotencyKey,
                    hash("${command.sessionId}|${command.channel}"),
                    entity.id,
                    now,
                )
            idempotency.saveAndFlush(idempotencyEntity)
            ChallengeIssueStart.Work(
                command.actorId,
                entity.id,
                session.id,
                session.supportCaseId,
                session.subjectType,
                session.subjectId,
                command.channel,
                idempotencyEntity.id,
                command.correlationId,
            )
        }

    @Transactional
    fun completeIssue(
        start: ChallengeIssueStart.Work,
        result: VerificationChallengeIssueResult,
    ): VerificationChallengeResource =
        boundary {
            cases.findLockedById(start.caseId) ?: notFound()
            val session = sessions.findLockedById(start.sessionId) ?: notFound()
            requireSessionOwner(session, start.actorId)
            val challengeEntity = challenges.findLockedById(start.challengeId) ?: notFound()
            if (challengeEntity.sessionId != session.id) conflict("Verification challenge binding is stale")
            completedResponse(start.idempotencyId, VerificationChallengeResource::class.java)?.let { return@boundary it }
            if (challengeEntity.state == ChallengeState.REVOKED) {
                val response = challengeEntity.toResource()
                val now = clock.instant()
                completedCommand(start.idempotencyId, response, 201, now)
                audits.appendAll(
                    listOf(challengeEntity.audit("SUPPORT_VERIFICATION_CHALLENGE_ISSUED", start.actorId, start.correlationId, now)),
                )
                return@boundary response
            }
            check(challengeEntity.state == ChallengeState.PENDING_ISSUE) { "Challenge issue is already terminal" }
            val now = clock.instant()
            val aggregate = challengeEntity.toAggregate()
            when (result) {
                is VerificationChallengeIssueResult.Issued -> aggregate.completeIssue(result.opaqueProviderReference, now)
                is VerificationChallengeIssueResult.Unknown -> aggregate.markIssueUnknown()
            }
            challengeEntity.apply(aggregate, now)
            challenges.saveAndFlush(challengeEntity)
            val response = challengeEntity.toResource()
            completedCommand(start.idempotencyId, response, 201, now)
            audits.appendAll(
                listOf(
                    challengeEntity.audit(
                        action = "SUPPORT_VERIFICATION_CHALLENGE_ISSUED",
                        actorId = start.actorId,
                        correlationId = start.correlationId,
                        occurredAt = now,
                    ),
                ),
            )
            response
        }

    @Transactional
    fun beginVerify(command: VerifySupportChallengeCommand): ChallengeVerifyStart =
        boundary {
            validateIdempotency(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            val sessionId = challenges.findSessionIdById(command.challengeId) ?: notFound()
            val caseId = sessions.findCaseIdById(sessionId) ?: notFound()
            commandLock.lock(caseId, command.actorId, VERIFY_CHALLENGE, command.idempotencyKey)
            activeAssignedCase(caseId, command.actorId)
            val session = sessions.findLockedById(sessionId) ?: notFound()
            requireSessionOwner(session, command.actorId)
            activeLink(caseId, session.subjectLinkId)
            val challenge = challenges.findLockedById(command.challengeId) ?: notFound()
            if (challenge.sessionId != session.id) conflict("Verification challenge binding is stale")
            existingCommand(command.actorId, VERIFY_CHALLENGE, command.idempotencyKey)?.let { existing ->
                requirePayload(existing, hash(command.challengeId.toString()))
                return@boundary ChallengeVerifyStart.Replay(readCompleted(existing, VerificationResultResource::class.java))
            }
            val sessionAggregate = session.toAggregate(verifiedChannels(session.id))
            sessionAggregate.refresh(clock.instant())
            check(sessionAggregate.state == VerificationState.PENDING) { "Verification session is not pending" }
            session.apply(sessionAggregate, clock.instant())
            val aggregate = challenge.toAggregate()
            aggregate.claimVerification(clock.instant())
            challenge.apply(aggregate, null)
            challenges.saveAndFlush(challenge)
            sessions.saveAndFlush(session)
            val now = clock.instant()
            val idempotencyEntity =
                processingCommand(
                    command.actorId,
                    VERIFY_CHALLENGE,
                    command.idempotencyKey,
                    hash(command.challengeId.toString()),
                    challenge.id,
                    now,
                )
            idempotency.saveAndFlush(idempotencyEntity)
            ChallengeVerifyStart.Work(
                command.actorId,
                challenge.id,
                session.id,
                session.supportCaseId,
                requireNotNull(challenge.opaqueProviderReference),
                idempotencyEntity.id,
                command.correlationId,
            )
        }

    @Transactional
    fun completeVerify(
        start: ChallengeVerifyStart.Work,
        providerResult: VerificationChallengeVerifyResult,
    ): VerificationResultResource =
        boundary {
            cases.findLockedById(start.caseId) ?: notFound()
            val session = sessions.findLockedById(start.sessionId) ?: notFound()
            requireSessionOwner(session, start.actorId)
            val challenge = challenges.findLockedById(start.challengeId) ?: notFound()
            if (challenge.sessionId != session.id) conflict("Verification challenge binding is stale")
            completedResponse(start.idempotencyId, VerificationResultResource::class.java)?.let { return@boundary it }
            if (challenge.state == ChallengeState.REVOKED) {
                val now = clock.instant()
                val response =
                    VerificationResultResource(
                        challenge.toResource(),
                        session.state,
                        VerificationLevel.UNVERIFIED,
                        session.invalidAttempts,
                        null,
                    )
                completedCommand(start.idempotencyId, response, 200, now)
                audits.appendAll(
                    listOf(
                        session.audit(
                            "SUPPORT_VERIFICATION_ATTEMPT_RECORDED",
                            start.actorId,
                            start.correlationId,
                            now,
                            mapOf("outcome" to "DISCARDED_AFTER_REVOCATION", "sessionState" to session.state.name),
                            "support-verification-attempt-discarded:${challenge.id}",
                        ),
                    ),
                )
                return@boundary response
            }
            check(challenge.state == ChallengeState.VERIFYING) { "Verification challenge is not awaiting an outcome" }
            val previousChannels = verifiedChannels(session.id)
            val sessionAggregate = session.toAggregate(previousChannels)
            val challengeAggregate = challenge.toAggregate()
            val now = clock.instant()
            val outcome =
                when (providerResult) {
                    VerificationChallengeVerifyResult.VERIFIED -> ChallengeOutcome.VERIFIED
                    VerificationChallengeVerifyResult.INVALID -> ChallengeOutcome.INVALID
                    VerificationChallengeVerifyResult.UNKNOWN -> ChallengeOutcome.UNKNOWN
                }
            val completedState = challengeAggregate.complete(outcome, now)
            val effectiveOutcome =
                when (completedState) {
                    ChallengeState.VERIFIED -> ChallengeOutcome.VERIFIED
                    ChallengeState.INVALID -> ChallengeOutcome.INVALID
                    ChallengeState.VERIFICATION_UNKNOWN -> ChallengeOutcome.UNKNOWN
                    else -> error("Verification completion produced a non-terminal provider state")
                }
            challenge.apply(challengeAggregate, now)
            sessionAggregate.refresh(now)
            var lockedUntil: Instant? = null
            if (sessionAggregate.state == VerificationState.PENDING) {
                when (effectiveOutcome) {
                    ChallengeOutcome.VERIFIED -> sessionAggregate.recordVerifiedChannel(challenge.channel, now)
                    ChallengeOutcome.INVALID -> lockedUntil = sessionAggregate.recordInvalidAttempt(now)
                    ChallengeOutcome.UNKNOWN -> Unit
                }
            }
            session.apply(sessionAggregate, now)
            challenges.saveAndFlush(challenge)
            sessions.saveAndFlush(session)
            val attemptId = identifiers.next()
            attempts.saveAndFlush(
                VerificationAttemptEntity(
                    attemptId,
                    session.id,
                    challenge.id,
                    start.actorId,
                    challenge.channel,
                    effectiveOutcome.name,
                    now,
                ),
            )
            if (lockedUntil != null) {
                val existing = lockouts.findLocked(session.supportCaseId, session.subjectType, session.subjectId)
                if (existing == null) {
                    lockouts.saveAndFlush(
                        VerificationLockoutEntity(session.supportCaseId, session.subjectType, session.subjectId, lockedUntil, now),
                    )
                } else {
                    existing.lockedUntil = maxOf(existing.lockedUntil, lockedUntil)
                    existing.updatedAt = now
                    lockouts.saveAndFlush(existing)
                }
            }
            val response =
                VerificationResultResource(
                    challenge.toResource(),
                    session.state,
                    if (session.state == VerificationState.VERIFIED) session.requestedLevel else VerificationLevel.UNVERIFIED,
                    session.invalidAttempts,
                    lockedUntil,
                )
            completedCommand(start.idempotencyId, response, 200, now)
            audits.appendAll(
                listOf(
                    session.audit(
                        "SUPPORT_VERIFICATION_ATTEMPT_RECORDED",
                        start.actorId,
                        start.correlationId,
                        now,
                        mapOf("outcome" to effectiveOutcome.name, "sessionState" to session.state.name),
                        "support-verification-attempt:$attemptId",
                    ),
                ),
            )
            response
        }

    @Transactional
    fun revoke(command: RevokeVerificationSessionCommand): VerificationSessionResource =
        boundary {
            validateIdempotency(command.idempotencyKey)
            permissions.requireActive(command.actorId, OperatorPermission.SUPPORT_VERIFICATION_MANAGE)
            val caseId = sessions.findCaseIdById(command.sessionId) ?: notFound()
            commandLock.lock(caseId, command.actorId, REVOKE_SESSION, command.idempotencyKey)
            activeAssignedCase(caseId, command.actorId)
            val session = sessions.findLockedById(command.sessionId) ?: notFound()
            requireSessionOwner(session, command.actorId)
            replayOrExecute(
                command.actorId,
                REVOKE_SESSION,
                command.idempotencyKey,
                hash(command.sessionId.toString()),
                VerificationSessionResource::class.java,
                200,
            ) {
                val now = clock.instant()
                val aggregate = session.toAggregate(verifiedChannels(session.id))
                aggregate.revoke()
                session.apply(aggregate, now)
                sessions.saveAndFlush(session)
                challenges.findBySessionIdOrderByRequestedAtAscIdAsc(session.id).forEach { entity ->
                    val challenge = entity.toAggregate()
                    challenge.revoke()
                    entity.apply(challenge, now)
                    challenges.save(entity)
                }
                audits.appendAll(listOf(session.audit("SUPPORT_VERIFICATION_SESSION_REVOKED", command.actorId, command.correlationId, now)))
                session.toResource(challenges.findBySessionIdOrderByRequestedAtAscIdAsc(session.id))
            }
        }

    private fun activeAssignedCase(
        caseId: UUID,
        actorId: UUID,
    ): SupportCaseEntity {
        val supportCase = cases.findLockedById(caseId) ?: notFound()
        if (supportCase.currentAssigneeId != actorId) throw DomainFailure(FailureCode.ACCESS_DENIED, "SupportCase assignment is required")
        if (supportCase.state !in ACTIVE_CASE_STATES) conflict("Terminal SupportCase rejects verification and reveal")
        return supportCase
    }

    private fun activeLink(
        caseId: UUID,
        linkId: UUID,
    ): SupportCaseSubjectLinkEntity {
        val link = subjectLinks.findByIdAndSupportCaseId(linkId, caseId) ?: notFound()
        if (link.unlinkedAt != null) conflict("SupportCase subject link is inactive")
        return link
    }

    private fun requireSessionOwner(
        session: VerificationSessionEntity,
        actorId: UUID,
    ) {
        if (session.actorId != actorId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "VerificationSession belongs to another operator")
        }
    }

    private fun verifiedChannels(sessionId: UUID): Set<VerificationChannel> =
        challenges.findDistinctChannelsBySessionIdAndState(sessionId, ChallengeState.VERIFIED)

    private fun existingCommand(
        actorId: UUID,
        operation: String,
        key: String,
    ): SupportSecurityIdempotencyEntity? = idempotency.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)

    private fun processingCommand(
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

    private fun completedCommand(
        id: UUID,
        response: Any,
        status: Int,
        now: Instant,
    ) {
        val command = idempotency.findById(id).orElseThrow { IllegalStateException("Idempotency command is missing") }
        command.complete(status, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(command)
    }

    private fun <T> completedResponse(
        id: UUID,
        type: Class<T>,
    ): T? {
        val command = idempotency.findById(id).orElseThrow { IllegalStateException("Idempotency command is missing") }
        if (command.state != "COMPLETED" || command.responseBody == null) return null
        return objectMapper.readValue(command.responseBody, type)
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
        existingCommand(actorId, operation, key)?.let { existing ->
            requirePayload(existing, payloadHash)
            return readCompleted(existing, type)
        }
        val response = execute()
        val now = clock.instant()
        val command = processingCommand(actorId, operation, key, payloadHash, response.resourceId(), now)
        command.complete(status, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(command)
        return response
    }

    private fun Any.resourceId(): UUID =
        when (this) {
            is VerificationSessionResource -> sessionId
            is VerificationChallengeResource -> challengeId
            is VerificationResultResource -> challenge.challengeId
            else -> error("Unsupported Support security response")
        }

    private fun requirePayload(
        existing: SupportSecurityIdempotencyEntity,
        payloadHash: String,
    ) {
        if (existing.payloadHash != payloadHash) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused for another support security command")
        }
    }

    private fun <T> readCompleted(
        existing: SupportSecurityIdempotencyEntity,
        type: Class<T>,
    ): T {
        if (existing.state != "COMPLETED" || existing.responseBody == null) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Support security command is still in progress")
        }
        return objectMapper.readValue(existing.responseBody, type)
    }

    private fun validateIdempotency(value: String) {
        if (value != value.trim() || value.length !in 8..128 || value.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Idempotency-Key is invalid")
        }
    }

    private fun SupportSubjectType.toVerificationSubjectType(): VerificationSubjectType =
        when (this) {
            SupportSubjectType.CUSTOMER -> VerificationSubjectType.CUSTOMER

            SupportSubjectType.STORE -> VerificationSubjectType.STORE

            SupportSubjectType.DELIVERY -> VerificationSubjectType.DELIVERY

            SupportSubjectType.ORDER -> throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Order links cannot own personal-data verification",
            )
        }

    private fun <T> boundary(block: () -> T): T = persistenceBoundary(block)

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support verification persistence is unavailable").also {
                it.initCause(failure)
            }
        } catch (failure: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Support verification request is invalid").also { it.initCause(failure) }
        } catch (failure: IllegalStateException) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Support verification state does not allow this operation").also {
                it.initCause(failure)
            }
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Support verification resource was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private fun hash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val CREATE_SESSION = "CREATE_SESSION"
        const val ISSUE_CHALLENGE = "ISSUE_CHALLENGE"
        const val VERIFY_CHALLENGE = "VERIFY_CHALLENGE"
        const val REVOKE_SESSION = "REVOKE_SESSION"
        val ACTIVE_CASE_STATES = setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(90)
    }
}

private fun VerificationSession.toEntity(): VerificationSessionEntity =
    VerificationSessionEntity(
        id,
        caseId,
        subjectLinkId,
        subjectType,
        subjectId,
        actorId,
        purpose,
        actionScope,
        requestedLevel,
        state,
        invalidAttempts,
        startedAt,
        expiresAt,
        null,
        null,
        0,
    )

private fun VerificationSessionEntity.toAggregate(channels: Set<VerificationChannel>): VerificationSession =
    VerificationSession.restore(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        actorId,
        purpose,
        actionScope,
        requestedLevel,
        startedAt,
        expiresAt,
        state,
        invalidAttempts,
        channels,
    )

private fun VerificationSessionEntity.apply(
    aggregate: VerificationSession,
    now: Instant,
) {
    val previousState = state
    val previousInvalidAttempts = invalidAttempts
    state = aggregate.state
    invalidAttempts = aggregate.invalidAttempts
    if (previousState != state || previousInvalidAttempts != invalidAttempts) version += 1
    if (state == VerificationState.VERIFIED && verifiedAt == null) verifiedAt = now
    if (state == VerificationState.REVOKED && revokedAt == null) revokedAt = now
}

private fun VerificationSessionEntity.toResource(challenges: List<VerificationChallengeEntity>): VerificationSessionResource =
    VerificationSessionResource(
        id,
        supportCaseId,
        subjectLinkId,
        subjectType,
        subjectId,
        purpose,
        actionScope,
        requestedLevel,
        if (state == VerificationState.VERIFIED) requestedLevel else VerificationLevel.UNVERIFIED,
        state,
        invalidAttempts,
        startedAt,
        expiresAt,
        version,
        challenges.map(VerificationChallengeEntity::toResource),
    )

private fun VerificationChallenge.toEntity(): VerificationChallengeEntity =
    VerificationChallengeEntity(id, sessionId, channel, state, providerReference, requestedAt, expiresAt, null, 0)

private fun VerificationChallengeEntity.toAggregate(): VerificationChallenge =
    VerificationChallenge.restore(id, sessionId, channel, requestedAt, expiresAt, state, opaqueProviderReference)

private fun VerificationChallengeEntity.apply(
    aggregate: VerificationChallenge,
    completedAt: Instant?,
) {
    val previousState = state
    state = aggregate.state
    opaqueProviderReference = aggregate.providerReference
    if (previousState != state) version += 1
    if (state in TERMINAL_CHALLENGE_STATES) this.completedAt = completedAt
}

private fun VerificationChallengeEntity.toResource(): VerificationChallengeResource =
    VerificationChallengeResource(id, sessionId, channel, state, requestedAt, expiresAt)

private fun VerificationSessionEntity.audit(
    action: String,
    actorId: UUID,
    correlationId: String,
    occurredAt: Instant,
    summary: Map<String, String> = mapOf("event" to action, "state" to state.name),
    sourceReference: String = "support-verification:$id:$action:$version",
): AppendAuditRecordCommand =
    AppendAuditRecordCommand(
        actorId = actorId.toString(),
        actorType = AuditActorType.PLATFORM_OPERATOR,
        category = AuditCategory.SECURITY_AND_PERMISSION,
        action = action,
        targetType = "SUPPORT_VERIFICATION_SESSION",
        targetId = id,
        occurredAt = occurredAt,
        reason = "SUPPORT_VERIFICATION",
        afterSummary = summary,
        correlationId = correlationId,
        sourceReference = sourceReference,
    )

private fun VerificationChallengeEntity.audit(
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
        targetType = "SUPPORT_VERIFICATION_CHALLENGE",
        targetId = id,
        occurredAt = occurredAt,
        reason = "SUPPORT_VERIFICATION",
        afterSummary = mapOf("event" to action, "state" to state.name, "channel" to channel.name),
        correlationId = correlationId,
        sourceReference = "support-verification-challenge:$id:$action:$version",
    )

private val TERMINAL_CHALLENGE_STATES =
    setOf(
        ChallengeState.ISSUE_UNKNOWN,
        ChallengeState.VERIFIED,
        ChallengeState.INVALID,
        ChallengeState.VERIFICATION_UNKNOWN,
        ChallengeState.EXPIRED,
        ChallengeState.REVOKED,
    )
