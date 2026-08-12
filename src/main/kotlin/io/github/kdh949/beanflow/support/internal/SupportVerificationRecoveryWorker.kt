package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.domain.ChallengeState
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
internal class SupportVerificationRecoveryWorker(
    private val transactions: SupportVerificationRecoveryTransactions,
) {
    @Scheduled(
        initialDelayString = "\${beanflow.support-verification-recovery.initial-delay-ms:60000}",
        fixedDelayString = "\${beanflow.support-verification-recovery.fixed-delay-ms:10000}",
    )
    fun recoverExpiredWork() {
        repeat(MAX_BATCH) {
            if (!transactions.recoverOne()) return
        }
    }

    private companion object {
        const val MAX_BATCH = 50
    }
}

@Service
internal class SupportVerificationRecoveryTransactions(
    private val jdbcTemplate: JdbcTemplate,
    private val cases: SupportCaseJpaRepository,
    private val sessions: VerificationSessionJpaRepository,
    private val challenges: VerificationChallengeJpaRepository,
    private val attempts: VerificationAttemptJpaRepository,
    private val idempotency: SupportSecurityIdempotencyJpaRepository,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun recoverOne(): Boolean {
        val now = clock.instant()
        val candidate = findCandidate(now) ?: return false
        cases.findLockedById(candidate.caseId) ?: return true
        val session = sessions.findLockedById(candidate.sessionId) ?: return true
        val challenge = challenges.findLockedById(candidate.challengeId) ?: return true
        if (challenge.sessionId != session.id || challenge.state !in RECOVERABLE_STATES || challenge.expiresAt.isAfter(now)) return true

        val operation: String
        val status: Int
        val action: String
        val response: Any
        val command =
            when (challenge.state) {
                ChallengeState.PENDING_ISSUE -> {
                    operation = ISSUE_CHALLENGE
                    status = 201
                    action = "SUPPORT_VERIFICATION_CHALLENGE_ISSUED"
                    idempotency.findLockedProcessingByResourceIdAndOperation(challenge.id, operation)
                }

                ChallengeState.VERIFYING -> {
                    operation = VERIFY_CHALLENGE
                    status = 200
                    action = "SUPPORT_VERIFICATION_ATTEMPT_RECORDED"
                    idempotency.findLockedProcessingByResourceIdAndOperation(challenge.id, operation)
                }

                else -> {
                    error("Unreachable verification recovery state")
                }
            } ?: error("Recoverable verification challenge is missing its processing command")

        challenge.state =
            if (challenge.state == ChallengeState.PENDING_ISSUE) ChallengeState.ISSUE_UNKNOWN else ChallengeState.VERIFICATION_UNKNOWN
        challenge.completedAt = now
        challenge.version += 1
        challenges.saveAndFlush(challenge)

        if (session.state in EXPIRABLE_SESSION_STATES && !now.isBefore(session.expiresAt)) {
            session.state = VerificationState.EXPIRED
            session.version += 1
            sessions.saveAndFlush(session)
        }

        val challengeResource =
            VerificationChallengeResource(
                challenge.id,
                challenge.sessionId,
                challenge.channel,
                challenge.state,
                challenge.requestedAt,
                challenge.expiresAt,
            )
        if (operation == VERIFY_CHALLENGE) {
            if (!attempts.existsByChallengeId(challenge.id)) {
                attempts.saveAndFlush(
                    VerificationAttemptEntity(
                        identifiers.next(),
                        session.id,
                        challenge.id,
                        command.actorId,
                        challenge.channel,
                        "UNKNOWN",
                        now,
                    ),
                )
            }
            response =
                VerificationResultResource(
                    challengeResource,
                    session.state,
                    if (session.state == VerificationState.VERIFIED) session.requestedLevel else VerificationLevel.UNVERIFIED,
                    session.invalidAttempts,
                    null,
                )
        } else {
            response = challengeResource
        }
        command.complete(status, objectMapper.writeValueAsString(response), now)
        idempotency.saveAndFlush(command)
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = action,
                    targetType = if (operation == VERIFY_CHALLENGE) "SUPPORT_VERIFICATION_SESSION" else "SUPPORT_VERIFICATION_CHALLENGE",
                    targetId = if (operation == VERIFY_CHALLENGE) session.id else challenge.id,
                    occurredAt = now,
                    reason = "PROVIDER_OUTCOME_UNKNOWN",
                    afterSummary =
                        mapOf(
                            "event" to action,
                            "outcome" to "UNKNOWN",
                            "challengeState" to challenge.state.name,
                            "recovery" to "EXPIRED_PROCESSING_WORK",
                        ),
                    correlationId = "support-verification-recovery",
                    sourceReference = "support-verification-recovery:${challenge.id}:$operation",
                ),
            ),
        )
        return true
    }

    private fun findCandidate(now: Instant): RecoveryCandidate? =
        jdbcTemplate
            .query(
                """
                SELECT challenge.id AS challenge_id, challenge.session_id, session.support_case_id
                  FROM support_verification_challenge challenge
                  JOIN support_verification_session session ON session.id = challenge.session_id
                 WHERE challenge.state IN ('PENDING_ISSUE', 'VERIFYING')
                   AND challenge.expires_at <= ?
                 ORDER BY challenge.expires_at, challenge.id
                 LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    RecoveryCandidate(
                        rs.getObject("challenge_id", UUID::class.java),
                        rs.getObject("session_id", UUID::class.java),
                        rs.getObject("support_case_id", UUID::class.java),
                    )
                },
                Timestamp.from(now),
            ).singleOrNull()

    private data class RecoveryCandidate(
        val challengeId: UUID,
        val sessionId: UUID,
        val caseId: UUID,
    )

    private companion object {
        const val ISSUE_CHALLENGE = "ISSUE_CHALLENGE"
        const val VERIFY_CHALLENGE = "VERIFY_CHALLENGE"
        val RECOVERABLE_STATES = setOf(ChallengeState.PENDING_ISSUE, ChallengeState.VERIFYING)
        val EXPIRABLE_SESSION_STATES = setOf(VerificationState.PENDING, VerificationState.VERIFIED)
    }
}
