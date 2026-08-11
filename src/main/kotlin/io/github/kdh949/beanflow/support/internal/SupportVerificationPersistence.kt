package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.ChallengeState
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "support_verification_session")
internal class VerificationSessionEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(name = "subject_link_id", nullable = false)
    val subjectLinkId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    val subjectType: VerificationSubjectType,
    @Column(name = "subject_id", nullable = false)
    val subjectId: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val purpose: VerificationPurpose,
    @Enumerated(EnumType.STRING)
    @Column(name = "action_scope", nullable = false, length = 32)
    val actionScope: VerificationActionScope,
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_level", nullable = false, length = 16)
    val requestedLevel: VerificationLevel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: VerificationState,
    @Column(name = "invalid_attempts", nullable = false)
    var invalidAttempts: Int,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "verified_at")
    var verifiedAt: Instant?,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
)

@Entity
@Table(name = "support_verification_challenge")
internal class VerificationChallengeEntity(
    @Id
    val id: UUID,
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val channel: VerificationChannel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var state: ChallengeState,
    @Column(name = "opaque_provider_reference", length = 1000)
    var opaqueProviderReference: String?,
    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "completed_at")
    var completedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
)

@Entity
@Table(name = "support_verification_attempt")
internal class VerificationAttemptEntity(
    @Id
    val id: UUID,
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,
    @Column(name = "challenge_id", nullable = false)
    val challengeId: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val channel: VerificationChannel,
    @Column(nullable = false, length = 16)
    val outcome: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

internal data class VerificationLockoutId(
    var supportCaseId: UUID = UUID(0, 0),
    var subjectType: VerificationSubjectType = VerificationSubjectType.CUSTOMER,
    var subjectId: UUID = UUID(0, 0),
) : Serializable

@Entity
@IdClass(VerificationLockoutId::class)
@Table(name = "support_verification_lockout")
internal class VerificationLockoutEntity(
    @Id
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    val subjectType: VerificationSubjectType,
    @Id
    @Column(name = "subject_id", nullable = false)
    val subjectId: UUID,
    @Column(name = "locked_until", nullable = false)
    var lockedUntil: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

@Entity
@Table(name = "support_security_command_idempotency")
internal class SupportSecurityIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false, length = 32)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "resource_id", nullable = false)
    val resourceId: UUID,
    @Column(nullable = false, length = 16)
    var state: String,
    @Column(name = "response_status")
    var responseStatus: Int?,
    @Column(name = "response_body", columnDefinition = "text")
    var responseBody: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "completed_at")
    var completedAt: Instant?,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
) {
    fun complete(
        status: Int,
        body: String,
        at: Instant,
    ) {
        check(state == "PROCESSING")
        state = "COMPLETED"
        responseStatus = status
        responseBody = body
        completedAt = at
    }
}

internal interface VerificationSessionJpaRepository : JpaRepository<VerificationSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from VerificationSessionEntity session where session.id = :id")
    fun findLockedById(@Param("id") id: UUID): VerificationSessionEntity?

    @Query("select session from VerificationSessionEntity session where session.id = :id")
    fun findCurrentById(@Param("id") id: UUID): VerificationSessionEntity?

    @Query("select session.supportCaseId from VerificationSessionEntity session where session.id = :id")
    fun findCaseIdById(@Param("id") id: UUID): UUID?

    @Query(
        "select session from VerificationSessionEntity session where session.supportCaseId = :caseId " +
            "and session.state in :states",
    )
    fun findByCaseIdAndStates(
        @Param("caseId") caseId: UUID,
        @Param("states") states: Set<VerificationState>,
    ): List<VerificationSessionEntity>
}

internal interface VerificationChallengeJpaRepository : JpaRepository<VerificationChallengeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from VerificationChallengeEntity challenge where challenge.id = :id")
    fun findLockedById(@Param("id") id: UUID): VerificationChallengeEntity?

    @Query("select challenge.sessionId from VerificationChallengeEntity challenge where challenge.id = :id")
    fun findSessionIdById(@Param("id") id: UUID): UUID?

    fun findBySessionIdOrderByRequestedAtAscIdAsc(sessionId: UUID): List<VerificationChallengeEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select challenge from VerificationChallengeEntity challenge where challenge.sessionId = :sessionId " +
            "order by challenge.requestedAt, challenge.id",
    )
    fun findLockedBySessionIdOrderByRequestedAtAscIdAsc(@Param("sessionId") sessionId: UUID): List<VerificationChallengeEntity>

    @Query(
        "select distinct challenge.channel from VerificationChallengeEntity challenge " +
            "where challenge.sessionId = :sessionId and challenge.state = :state",
    )
    fun findDistinctChannelsBySessionIdAndState(
        @Param("sessionId") sessionId: UUID,
        @Param("state") state: ChallengeState,
    ): Set<VerificationChannel>
}

internal interface VerificationAttemptJpaRepository : JpaRepository<VerificationAttemptEntity, UUID> {
    fun existsByChallengeId(challengeId: UUID): Boolean
}

internal interface VerificationLockoutJpaRepository : JpaRepository<VerificationLockoutEntity, VerificationLockoutId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select lockout from VerificationLockoutEntity lockout " +
            "where lockout.supportCaseId = :caseId and lockout.subjectType = :subjectType and lockout.subjectId = :subjectId",
    )
    fun findLocked(
        @Param("caseId") caseId: UUID,
        @Param("subjectType") subjectType: VerificationSubjectType,
        @Param("subjectId") subjectId: UUID,
    ): VerificationLockoutEntity?
}

internal interface SupportSecurityIdempotencyJpaRepository : JpaRepository<SupportSecurityIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): SupportSecurityIdempotencyEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select command from SupportSecurityIdempotencyEntity command " +
            "where command.resourceId = :resourceId and command.operation = :operation and command.state = 'PROCESSING'",
    )
    fun findLockedProcessingByResourceIdAndOperation(
        @Param("resourceId") resourceId: UUID,
        @Param("operation") operation: String,
    ): SupportSecurityIdempotencyEntity?
}
