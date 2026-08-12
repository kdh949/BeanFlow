package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionCase
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionOutcome
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionPlan
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionResponsibility
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionState
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStep
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStepState
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStepType
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
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
@Table(name = "support_post_acceptance_resolution")
internal class PostAcceptanceResolutionEntity(
    @Id
    val id: UUID,
    @Column(name = "support_case_id", nullable = false)
    val supportCaseId: UUID,
    @Column(name = "request_id", nullable = false)
    val supportActionRequestId: UUID,
    @Column(name = "revision_id", nullable = false)
    val supportActionRevisionId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val action: SupportActionType,
    @Column(name = "action_payload_digest", nullable = false, length = 64)
    val actionPayloadDigest: String,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "trigger_order_state", nullable = false, length = 32)
    val triggerOrderState: String,
    @Column(name = "trigger_order_version", nullable = false)
    val triggerOrderVersion: Long,
    @Column(name = "requester_actor_id", nullable = false)
    val requesterActorId: UUID,
    @Column(name = "command_actor_id", nullable = false)
    val commandActorId: UUID,
    @Column(name = "executor_actor_id", nullable = false)
    val executorActorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val outcome: PostAcceptanceResolutionOutcome,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val responsibility: PostAcceptanceResolutionResponsibility,
    @Column(name = "cash_refund_krw", nullable = false)
    val cashRefundKrw: Long,
    @Column(name = "restore_points", nullable = false)
    val restorePoints: Boolean,
    @Column(name = "restore_coupon", nullable = false)
    val restoreCoupon: Boolean,
    @Column(name = "settlement_adjustment_krw")
    val settlementAdjustmentKrw: Long?,
    @Column(name = "evidence_digest", nullable = false, length = 64)
    val evidenceDigest: String,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var state: PostAcceptanceResolutionState,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
    @Column(nullable = false)
    var version: Long,
) {
    fun toDomain(steps: List<PostAcceptanceResolutionStepEntity>): PostAcceptanceResolutionCase =
        PostAcceptanceResolutionCase.restore(
            id = id,
            supportCaseId = supportCaseId,
            supportActionRequestId = supportActionRequestId,
            supportActionRevisionId = supportActionRevisionId,
            revisionNumber = revisionNumber,
            actionPayloadDigest = actionPayloadDigest,
            orderId = orderId,
            triggerOrderState = triggerOrderState,
            triggerOrderVersion = triggerOrderVersion,
            requesterActorId = requesterActorId,
            executorActorId = executorActorId,
            plan =
                PostAcceptanceResolutionPlan(
                    outcome,
                    responsibility,
                    cashRefundKrw,
                    restorePoints,
                    restoreCoupon,
                    settlementAdjustmentKrw,
                    evidenceDigest,
                ),
            createdAt = createdAt,
            state = state,
            steps = steps.map(PostAcceptanceResolutionStepEntity::toDomain),
            updatedAt = updatedAt,
            version = version,
        )

    fun apply(domain: PostAcceptanceResolutionCase) {
        state = domain.state
        updatedAt = domain.updatedAt
        version = domain.version
    }
}

@Entity
@Table(name = "support_post_acceptance_resolution_step")
internal class PostAcceptanceResolutionStepEntity(
    @Id
    val id: UUID,
    @Column(name = "resolution_id", nullable = false)
    val resolutionId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 32)
    val stepType: PostAcceptanceResolutionStepType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var state: PostAcceptanceResolutionStepState,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant?,
    @Column(name = "result_reference", length = 240)
    var resultReference: String?,
    @Column(name = "failure_code", length = 80)
    var failureCode: String?,
    @Column(name = "claim_token")
    var claimToken: UUID?,
    @Column(name = "claim_until")
    var claimUntil: Instant?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(nullable = false)
    var version: Long,
) {
    fun toDomain(): PostAcceptanceResolutionStep =
        PostAcceptanceResolutionStep.restore(
            id,
            stepType,
            sourceReference,
            payloadHash,
            state,
            attemptCount,
            nextAttemptAt,
            resultReference,
            failureCode,
            claimToken,
            claimUntil,
            updatedAt,
            version,
        )

    fun apply(domain: PostAcceptanceResolutionStep) {
        state = domain.state
        attemptCount = domain.attemptCount
        nextAttemptAt = domain.nextAttemptAt
        resultReference = domain.resultReference
        failureCode = domain.failureCode
        claimToken = domain.claimToken
        claimUntil = domain.claimUntil
        updatedAt = domain.updatedAt
        version = domain.version
    }
}

internal interface PostAcceptanceResolutionJpaRepository : JpaRepository<PostAcceptanceResolutionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resolution from PostAcceptanceResolutionEntity resolution where resolution.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PostAcceptanceResolutionEntity?

    fun findBySupportActionRequestId(supportActionRequestId: UUID): PostAcceptanceResolutionEntity?

    fun findByCommandActorIdAndIdempotencyKey(
        commandActorId: UUID,
        idempotencyKey: String,
    ): PostAcceptanceResolutionEntity?
}

internal enum class PostAcceptanceResolutionCommandOperation {
    EXECUTE,
    RECONCILE,
}

@Entity
@Table(name = "support_post_acceptance_resolution_command")
internal class PostAcceptanceResolutionCommandEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val operation: PostAcceptanceResolutionCommandOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "resolution_id", nullable = false)
    val resolutionId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface PostAcceptanceResolutionCommandJpaRepository : JpaRepository<PostAcceptanceResolutionCommandEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: PostAcceptanceResolutionCommandOperation,
        idempotencyKey: String,
    ): PostAcceptanceResolutionCommandEntity?
}

internal interface PostAcceptanceResolutionStepJpaRepository : JpaRepository<PostAcceptanceResolutionStepEntity, UUID> {
    fun findByResolutionIdOrderByStepTypeAsc(resolutionId: UUID): List<PostAcceptanceResolutionStepEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select step from PostAcceptanceResolutionStepEntity step
         where step.resolutionId = :resolutionId and step.stepType = :stepType
        """,
    )
    fun findLocked(
        @Param("resolutionId") resolutionId: UUID,
        @Param("stepType") stepType: PostAcceptanceResolutionStepType,
    ): PostAcceptanceResolutionStepEntity?
}
