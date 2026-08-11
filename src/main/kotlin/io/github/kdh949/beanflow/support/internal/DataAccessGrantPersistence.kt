package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.DataAccessGrantState
import io.github.kdh949.beanflow.support.internal.domain.DataAccessReasonCode
import io.github.kdh949.beanflow.support.internal.domain.DataAccessRisk
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
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
@Table(name = "support_data_access_grant")
internal class DataAccessGrantEntity(
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
    @Column(name = "requester_id", nullable = false)
    val requesterId: UUID,
    @Column(name = "verification_session_id", nullable = false)
    val verificationSessionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val purpose: VerificationPurpose,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    val reasonCode: DataAccessReasonCode,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val risk: DataAccessRisk,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var state: DataAccessGrantState,
    @Column(name = "max_reveals", nullable = false)
    val maxReveals: Int,
    @Column(name = "reserved_reveals", nullable = false)
    var reservedReveals: Int,
    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant,
    @Column(name = "expires_at")
    var expiresAt: Instant?,
    @Column(name = "approver_id")
    var approverId: UUID?,
    @Column(name = "approved_at")
    var approvedAt: Instant?,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
)

internal data class DataAccessGrantFieldId(
    var grantId: UUID = UUID(0, 0),
    var field: SupportPersonalDataField = SupportPersonalDataField.CUSTOMER_DISPLAY_NAME,
) : Serializable

@Entity
@IdClass(DataAccessGrantFieldId::class)
@Table(name = "support_data_access_grant_field")
internal class DataAccessGrantFieldEntity(
    @Id
    @Column(name = "grant_id", nullable = false)
    val grantId: UUID,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    val field: SupportPersonalDataField,
)

@Entity
@Table(name = "support_data_access_grant_decision")
internal class DataAccessGrantDecisionEntity(
    @Id
    val id: UUID,
    @Column(name = "grant_id", nullable = false)
    val grantId: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false, length = 16)
    val decision: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    val reasonCode: DataAccessReasonCode,
    @Column(name = "grant_version", nullable = false)
    val grantVersion: Long,
    @Column(name = "decided_at", nullable = false)
    val decidedAt: Instant,
)

@Entity
@Table(name = "support_reveal_attempt")
internal class RevealAttemptEntity(
    @Id
    val id: UUID,
    @Column(name = "access_path", nullable = false, length = 16)
    val accessPath: String,
    @Column(name = "grant_id")
    val grantId: UUID?,
    @Column(name = "break_glass_request_id")
    val breakGlassRequestId: UUID?,
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
    @Column(nullable = false, length = 16)
    var state: String,
    @Column(name = "failure_class", length = 32)
    var failureClass: String?,
    @Column(name = "reserved_at", nullable = false)
    val reservedAt: Instant,
    @Column(name = "completed_at")
    var completedAt: Instant?,
) {
    fun revealed(now: Instant) {
        check(state == "RESERVED")
        state = "REVEALED"
        completedAt = now
    }

    fun failed(
        failure: String,
        now: Instant,
    ) {
        check(state == "RESERVED")
        state = "FAILED"
        failureClass = failure
        completedAt = now
    }
}

internal data class RevealAttemptFieldId(
    var revealAttemptId: UUID = UUID(0, 0),
    var field: SupportPersonalDataField = SupportPersonalDataField.CUSTOMER_DISPLAY_NAME,
) : Serializable

@Entity
@IdClass(RevealAttemptFieldId::class)
@Table(name = "support_reveal_attempt_field")
internal class RevealAttemptFieldEntity(
    @Id
    @Column(name = "reveal_attempt_id", nullable = false)
    val revealAttemptId: UUID,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    val field: SupportPersonalDataField,
)

internal interface DataAccessGrantJpaRepository : JpaRepository<DataAccessGrantEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from DataAccessGrantEntity grant where grant.id = :id")
    fun findLockedById(@Param("id") id: UUID): DataAccessGrantEntity?
}

internal interface DataAccessGrantFieldJpaRepository : JpaRepository<DataAccessGrantFieldEntity, DataAccessGrantFieldId> {
    fun findByGrantIdOrderByFieldAsc(grantId: UUID): List<DataAccessGrantFieldEntity>
}

internal interface DataAccessGrantDecisionJpaRepository : JpaRepository<DataAccessGrantDecisionEntity, UUID>

internal interface RevealAttemptJpaRepository : JpaRepository<RevealAttemptEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from RevealAttemptEntity attempt where attempt.id = :id")
    fun findLockedById(@Param("id") id: UUID): RevealAttemptEntity?
}

internal interface RevealAttemptFieldJpaRepository : JpaRepository<RevealAttemptFieldEntity, RevealAttemptFieldId> {
    fun findByRevealAttemptIdOrderByFieldAsc(revealAttemptId: UUID): List<RevealAttemptFieldEntity>
}
