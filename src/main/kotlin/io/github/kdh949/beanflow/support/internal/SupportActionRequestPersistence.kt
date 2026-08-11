package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.SupportActionApprovalRoute
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequest
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRequestState
import io.github.kdh949.beanflow.support.internal.domain.SupportActionRevision
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalStepState
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalStepType
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

internal enum class SupportActionTargetType {
    ORDER,
}

@Entity
@Table(name = "support_action_request")
internal class SupportActionRequestEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val action: SupportActionType,
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 24)
    val targetType: SupportActionTargetType,
    @Column(name = "target_id", nullable = false)
    val targetId: UUID,
    @Column(name = "requester_actor_id", nullable = false)
    val requesterActorId: UUID,
    @Column(name = "executor_actor_id", nullable = false)
    var executorActorId: UUID,
    @Column(name = "current_revision_number", nullable = false)
    var currentRevisionNumber: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_route", nullable = false, length = 48)
    val approvalRoute: SupportActionApprovalRoute,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var state: SupportActionRequestState,
    @Column(name = "support_approver_actor_id")
    var supportApproverActorId: UUID?,
    @Column(name = "operations_approver_actor_id")
    var operationsApproverActorId: UUID?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(nullable = false)
    var version: Long,
) {
    fun toAggregate(revision: SupportActionRevisionEntity): SupportActionRequest =
        SupportActionRequest.reconstitute(
            id = id,
            supportCaseId = supportCaseId,
            requesterActorId = requesterActorId,
            executorActorId = executorActorId,
            route = approvalRoute,
            revision = revision.toDomain(),
            state = state,
            supportApproverActorId = supportApproverActorId,
            operationsApproverActorId = operationsApproverActorId,
            version = version,
            lastChangedAt = updatedAt,
        )

    fun apply(aggregate: SupportActionRequest) {
        executorActorId = aggregate.executorActorId
        currentRevisionNumber = aggregate.currentRevision.revisionNumber
        state = aggregate.state
        supportApproverActorId = aggregate.supportApproverActorId
        operationsApproverActorId = aggregate.operationsApproverActorId
        updatedAt = aggregate.currentRevision.createdAt.coerceAtLeast(updatedAt)
        version = aggregate.version
    }

    fun apply(
        aggregate: SupportActionRequest,
        changedAt: Instant,
    ) {
        apply(aggregate)
        updatedAt = changedAt
    }
}

@Entity
@Table(name = "support_action_revision")
internal class SupportActionRevisionEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val action: SupportActionType,
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 24)
    val targetType: SupportActionTargetType,
    @Column(name = "target_id", nullable = false)
    val targetId: UUID,
    @Column(name = "action_payload_digest", nullable = false, length = 64)
    val actionPayloadDigest: String,
    @Column(name = "verification_session_id", nullable = false)
    val verificationSessionId: UUID,
    @Column(name = "policy_version", nullable = false, length = 160)
    val policyVersion: String,
    @Column(name = "target_version", nullable = false)
    val targetVersion: Long,
    @Column(name = "amount_krw")
    val amountKrw: Long?,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "evidence_digest", nullable = false, length = 64)
    val evidenceDigest: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "created_by_actor_id", nullable = false)
    val createdByActorId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    fun toDomain(): SupportActionRevision =
        SupportActionRevision(
            id,
            revisionNumber,
            action,
            targetId,
            actionPayloadDigest,
            verificationSessionId,
            policyVersion,
            targetVersion,
            amountKrw,
            reason,
            evidenceDigest,
            expiresAt,
            createdByActorId,
            createdAt,
        )
}

@Entity
@Table(name = "support_action_approval_step")
internal class SupportActionApprovalStepEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "revision_id", nullable = false)
    val revisionId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 24)
    val stepType: SupportApprovalStepType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val state: SupportApprovalStepState,
    @Column(name = "decided_by_actor_id")
    val decidedByActorId: UUID?,
    @Column(name = "decision_reason", nullable = false, length = 500)
    val decisionReason: String,
    @Column(name = "decided_at", nullable = false)
    val decidedAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

@Entity
@Table(name = "support_action_reassignment")
internal class SupportActionReassignmentEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Column(name = "previous_executor_actor_id", nullable = false)
    val previousExecutorActorId: UUID,
    @Column(name = "current_executor_actor_id", nullable = false)
    val currentExecutorActorId: UUID,
    @Column(name = "reassigned_by_actor_id", nullable = false)
    val reassignedByActorId: UUID,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "case_version", nullable = false)
    val caseVersion: Long,
    @Column(name = "request_version", nullable = false)
    val requestVersion: Long,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

internal enum class SupportActionCommandOperation {
    CREATE_REQUEST,
    REVISE_REQUEST,
    MANAGER_DECISION,
    REASSIGN_REQUEST,
}

@Entity
@Table(name = "support_action_command_idempotency")
internal class SupportActionCommandIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val operation: SupportActionCommandOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,
    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    val responseBody: String,
    @Column(name = "failure_code", length = 64)
    val failureCode: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface SupportActionRequestJpaRepository : JpaRepository<SupportActionRequestEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from SupportActionRequestEntity request where request.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportActionRequestEntity?
}

internal interface SupportActionRevisionJpaRepository : JpaRepository<SupportActionRevisionEntity, UUID> {
    fun findByRequestIdAndRevisionNumber(
        requestId: UUID,
        revisionNumber: Int,
    ): SupportActionRevisionEntity?

    fun findByRequestIdOrderByRevisionNumberAsc(requestId: UUID): List<SupportActionRevisionEntity>
}

internal interface SupportActionApprovalStepJpaRepository : JpaRepository<SupportActionApprovalStepEntity, UUID> {
    fun findByRequestIdAndRevisionNumberOrderByStepTypeAsc(
        requestId: UUID,
        revisionNumber: Int,
    ): List<SupportActionApprovalStepEntity>

    fun existsByRequestIdAndRevisionNumberAndStepType(
        requestId: UUID,
        revisionNumber: Int,
        stepType: SupportApprovalStepType,
    ): Boolean
}

internal interface SupportActionReassignmentJpaRepository : JpaRepository<SupportActionReassignmentEntity, UUID>

internal interface SupportActionCommandIdempotencyJpaRepository : JpaRepository<SupportActionCommandIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: SupportActionCommandOperation,
        idempotencyKey: String,
    ): SupportActionCommandIdempotencyEntity?
}

private fun Instant.coerceAtLeast(other: Instant): Instant = if (isBefore(other)) other else this
