package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationState
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
@Table(name = "operations_support_investigation_case")
internal class OperationsSupportInvestigationEntity(
    @Id
    val id: UUID,
    @Column(name = "support_action_request_id", nullable = false)
    val supportActionRequestId: UUID,
    @Column(name = "support_action_revision_id", nullable = false)
    val supportActionRevisionId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Column(name = "requester_actor_id", nullable = false)
    val requesterActorId: UUID,
    @Column(name = "support_approver_actor_id")
    val supportApproverActorId: UUID?,
    @Column(name = "executor_actor_id", nullable = false)
    val executorActorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: OperationsSupportInvestigationState,
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "decided_by_actor_id")
    var decidedByActorId: UUID?,
    @Column(name = "decision_reason", length = 500)
    var decisionReason: String?,
    @Column(name = "decision_evidence_digest", length = 64)
    var decisionEvidenceDigest: String?,
    @Column(name = "decided_at")
    var decidedAt: Instant?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(nullable = false)
    var version: Long,
)

internal enum class OperationsSupportInvestigationIdempotencyOperation {
    DECIDE,
}

@Entity
@Table(name = "operations_support_investigation_idempotency")
internal class OperationsSupportInvestigationIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val operation: OperationsSupportInvestigationIdempotencyOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "investigation_id", nullable = false)
    val investigationId: UUID,
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

internal interface OperationsSupportInvestigationJpaRepository : JpaRepository<OperationsSupportInvestigationEntity, UUID> {
    fun findBySupportActionRequestIdAndRevisionNumber(
        supportActionRequestId: UUID,
        revisionNumber: Int,
    ): OperationsSupportInvestigationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select investigation from OperationsSupportInvestigationEntity investigation where investigation.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): OperationsSupportInvestigationEntity?
}

internal interface OperationsSupportInvestigationIdempotencyJpaRepository :
    JpaRepository<OperationsSupportInvestigationIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: OperationsSupportInvestigationIdempotencyOperation,
        idempotencyKey: String,
    ): OperationsSupportInvestigationIdempotencyEntity?
}
