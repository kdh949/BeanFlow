package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.SupportRequesterType
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

internal enum class SupportInteractionChannel {
    PHONE,
    CHAT,
    EMAIL,
    IN_PERSON,
    SYSTEM,
}

internal enum class SupportInteractionDirection {
    INBOUND,
    OUTBOUND,
    INTERNAL,
}

internal enum class SupportSubjectType {
    CUSTOMER,
    STORE,
    ORDER,
    DELIVERY,
}

internal enum class SupportSubjectRelationship {
    REQUESTER,
    AFFECTED_CUSTOMER,
    AFFECTED_STORE,
    RELATED_ORDER,
    RELATED_DELIVERY,
    OTHER,
}

@Entity
@Table(name = "support_case")
internal class SupportCaseEntity(
    @Id
    val id: UUID,
    @Column(name = "external_reference", length = 200)
    val externalReference: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false, length = 32)
    val requesterType: SupportRequesterType,
    @Column(name = "requester_reference", nullable = false, length = 200)
    val requesterReference: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val category: SupportInquiryCategory,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val priority: SupportCasePriority,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: SupportCaseState,
    @Column(name = "current_assignee_id", nullable = false)
    var currentAssigneeId: UUID,
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant,
    @Column(name = "last_changed_at", nullable = false)
    var lastChangedAt: Instant,
    @Column(name = "closed_at")
    var closedAt: Instant?,
    @Column(nullable = false)
    var version: Long,
    @Column(name = "retention_policy_version_id", nullable = false)
    val retentionPolicyVersionId: Long,
    @Column(name = "retention_policy_category", nullable = false, length = 48)
    val retentionPolicyCategory: String = "SUPPORT_CASE",
)

@Entity
@Table(name = "support_case_assignment_history")
internal class SupportCaseAssignmentHistoryEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(nullable = false)
    val sequence: Int,
    @Column(name = "previous_assignee_id")
    val previousAssigneeId: UUID?,
    @Column(name = "current_assignee_id", nullable = false)
    val currentAssigneeId: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "case_version", nullable = false)
    val caseVersion: Long,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

@Entity
@Table(name = "support_case_state_history")
internal class SupportCaseStateHistoryEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(nullable = false)
    val sequence: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", length = 16)
    val previousState: SupportCaseState?,
    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 16)
    val currentState: SupportCaseState,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "case_version", nullable = false)
    val caseVersion: Long,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

@Entity
@Table(name = "support_case_interaction")
internal class SupportCaseInteractionEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(nullable = false)
    val sequence: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val channel: SupportInteractionChannel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val direction: SupportInteractionDirection,
    @Column(name = "redacted_summary", nullable = false, length = 1000)
    val redactedSummary: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
    @Column(name = "recorded_by_actor_id", nullable = false)
    val recordedByActorId: UUID,
    @Column(name = "retention_policy_version_id", nullable = false)
    val retentionPolicyVersionId: Long,
    @Column(name = "retention_policy_category", nullable = false, length = 48)
    val retentionPolicyCategory: String = "SUPPORT_CASE",
)

@Entity
@Table(name = "support_case_note")
internal class SupportCaseNoteEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(nullable = false)
    val sequence: Int,
    @Column(nullable = false, length = 2000)
    val content: String,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "author_id", nullable = false)
    val authorId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_policy_version_id", nullable = false)
    val retentionPolicyVersionId: Long,
    @Column(name = "retention_policy_category", nullable = false, length = 48)
    val retentionPolicyCategory: String = "SUPPORT_CASE",
)

@Entity
@Table(name = "support_case_subject_link")
internal class SupportCaseSubjectLinkEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    val subjectType: SupportSubjectType,
    @Column(name = "subject_id", nullable = false)
    val subjectId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val relationship: SupportSubjectRelationship,
    @Column(name = "linked_by_actor_id", nullable = false)
    val linkedByActorId: UUID,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "linked_at", nullable = false)
    val linkedAt: Instant,
    @Column(name = "unlinked_by_actor_id")
    var unlinkedByActorId: UUID? = null,
    @Column(name = "unlink_reason", length = 500)
    var unlinkReason: String? = null,
    @Column(name = "unlinked_at")
    var unlinkedAt: Instant? = null,
    @Column(name = "unlink_case_version")
    var unlinkCaseVersion: Long? = null,
) {
    fun unlink(
        actorId: UUID,
        reason: String,
        occurredAt: Instant,
        caseVersion: Long,
    ) {
        check(unlinkedAt == null) { "SupportCase subject link is already unlinked" }
        require(!occurredAt.isBefore(linkedAt)) { "SupportCase subject link time cannot move backward" }
        unlinkedByActorId = actorId
        unlinkReason = reason
        unlinkedAt = occurredAt
        unlinkCaseVersion = caseVersion
    }
}

@Entity
@Table(name = "support_case_command_idempotency")
internal class SupportCaseIdempotencyEntity(
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
    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,
    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    val responseBody: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface SupportCaseJpaRepository : JpaRepository<SupportCaseEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select supportCase from SupportCaseEntity supportCase where supportCase.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportCaseEntity?
}

internal interface SupportCaseAssignmentHistoryJpaRepository : JpaRepository<SupportCaseAssignmentHistoryEntity, UUID> {
    @Query(
        "select coalesce(max(history.sequence), -1) + 1 from SupportCaseAssignmentHistoryEntity history where history.supportCaseId = :caseId",
    )
    fun nextSequence(
        @Param("caseId") caseId: UUID,
    ): Int
}

internal interface SupportCaseStateHistoryJpaRepository : JpaRepository<SupportCaseStateHistoryEntity, UUID> {
    @Query(
        "select coalesce(max(history.sequence), -1) + 1 from SupportCaseStateHistoryEntity history where history.supportCaseId = :caseId",
    )
    fun nextSequence(
        @Param("caseId") caseId: UUID,
    ): Int
}

internal interface SupportCaseInteractionJpaRepository : JpaRepository<SupportCaseInteractionEntity, UUID> {
    @Query(
        "select coalesce(max(interaction.sequence), -1) + 1 from SupportCaseInteractionEntity interaction where interaction.supportCaseId = :caseId",
    )
    fun nextSequence(
        @Param("caseId") caseId: UUID,
    ): Int
}

internal interface SupportCaseNoteJpaRepository : JpaRepository<SupportCaseNoteEntity, UUID> {
    @Query("select coalesce(max(note.sequence), -1) + 1 from SupportCaseNoteEntity note where note.supportCaseId = :caseId")
    fun nextSequence(
        @Param("caseId") caseId: UUID,
    ): Int
}

internal interface SupportCaseSubjectLinkJpaRepository : JpaRepository<SupportCaseSubjectLinkEntity, UUID> {
    fun findByIdAndSupportCaseId(
        id: UUID,
        supportCaseId: UUID,
    ): SupportCaseSubjectLinkEntity?

    fun findBySupportCaseIdAndUnlinkedAtIsNullOrderByLinkedAtAsc(supportCaseId: UUID): List<SupportCaseSubjectLinkEntity>

    fun existsBySupportCaseIdAndSubjectTypeAndSubjectIdAndRelationshipAndUnlinkedAtIsNull(
        supportCaseId: UUID,
        subjectType: SupportSubjectType,
        subjectId: UUID,
        relationship: SupportSubjectRelationship,
    ): Boolean
}

internal interface SupportCaseIdempotencyJpaRepository : JpaRepository<SupportCaseIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): SupportCaseIdempotencyEntity?
}
