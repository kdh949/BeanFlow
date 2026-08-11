package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.BreakGlassReasonCode
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassState
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "support_break_glass_request")
internal class BreakGlassRequestEntity(
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    val field: SupportPersonalDataField,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val purpose: VerificationPurpose,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    val reasonCode: BreakGlassReasonCode,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var state: BreakGlassState,
    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant,
    @Column(name = "expires_at")
    var expiresAt: Instant?,
    @Column(name = "approver_id")
    var approverId: UUID?,
    @Column(name = "approved_at")
    var approvedAt: Instant?,
    @Column(name = "revealed_at")
    var revealedAt: Instant?,
    @Column(name = "reviewer_id")
    var reviewerId: UUID?,
    @Column(name = "reviewed_at")
    var reviewedAt: Instant?,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
)

@Entity
@Table(name = "support_break_glass_decision")
internal class BreakGlassDecisionEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "decision_type", nullable = false, length = 16)
    val decisionType: String,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false, length = 16)
    val decision: String,
    @Column(name = "reason_code", nullable = false, length = 32)
    val reasonCode: String,
    @Column(name = "request_version", nullable = false)
    val requestVersion: Long,
    @Column(name = "decided_at", nullable = false)
    val decidedAt: Instant,
)

@Entity
@Table(name = "support_security_notification_intent")
internal class SecurityNotificationIntentEntity(
    @Id
    val id: UUID,
    @Column(name = "break_glass_request_id", nullable = false)
    val breakGlassRequestId: UUID,
    @Column(name = "event_type", nullable = false, length = 24)
    val eventType: String,
    @Column(nullable = false, length = 24)
    var state: String,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,
    @Column(name = "last_failure_class", length = 32)
    var lastFailureClass: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

internal interface BreakGlassRequestJpaRepository : JpaRepository<BreakGlassRequestEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from BreakGlassRequestEntity request where request.id = :id")
    fun findLockedById(@Param("id") id: UUID): BreakGlassRequestEntity?

    @Query("select request.supportCaseId from BreakGlassRequestEntity request where request.id = :id")
    fun findCaseIdById(@Param("id") id: UUID): UUID?
}

internal interface BreakGlassDecisionJpaRepository : JpaRepository<BreakGlassDecisionEntity, UUID>

internal interface SecurityNotificationIntentJpaRepository : JpaRepository<SecurityNotificationIntentEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from SecurityNotificationIntentEntity intent where intent.id = :id")
    fun findLockedById(@Param("id") id: UUID): SecurityNotificationIntentEntity?
}
