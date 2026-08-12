package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.support.internal.domain.ConsumeSupportOrderChangeAuthorizationCommand
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorization
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorizationType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeCostResponsibility
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
@Table(name = "support_order_change_authorization")
internal class SupportOrderChangeAuthorizationEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val action: SupportActionType,
    @Enumerated(EnumType.STRING)
    @Column(name = "authorization_type", nullable = false, length = 16)
    val type: SupportOrderChangeAuthorizationType,
    @Column(name = "policy_version", nullable = false, length = 160)
    val policyVersion: String,
    @Column(name = "request_id")
    val requestId: UUID?,
    @Column(name = "revision_number")
    val revisionNumber: Int?,
    @Column(name = "action_payload_digest", length = 64)
    val actionPayloadDigest: String?,
    @Column(name = "target_version")
    val targetVersion: Long?,
    @Column(name = "authorized_by_actor_id", nullable = false)
    val authorizedByActorId: UUID,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "authorized_at", nullable = false)
    val authorizedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "max_successful_uses", nullable = false)
    val maxSuccessfulUses: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_responsibility", nullable = false, length = 16)
    val costResponsibility: SupportOrderChangeCostResponsibility,
    @Column(name = "revoked_at")
    val revokedAt: Instant?,
) {
    fun toDomain(uses: List<SupportOrderChangeAuthorizationUseEntity>): SupportOrderChangeAuthorization =
        SupportOrderChangeAuthorization.reconstitute(
            id,
            storeId,
            action,
            type,
            policyVersion,
            requestId,
            revisionNumber,
            actionPayloadDigest,
            targetVersion,
            authorizedByActorId,
            authorizedAt,
            expiresAt,
            maxSuccessfulUses,
            costResponsibility,
            revokedAt,
            uses.map {
                ConsumeSupportOrderChangeAuthorizationCommand(
                    it.executionId,
                    storeId,
                    action,
                    it.requestId,
                    it.revisionNumber,
                    it.actionPayloadDigest,
                    it.targetVersion,
                )
            },
        )
}

internal enum class SupportOrderChangeExecutionOutcome {
    EXECUTED,
    RESOLUTION_REQUIRED,
}

@Entity
@Table(name = "support_order_change_execution")
internal class SupportOrderChangeExecutionEntity(
    @Id
    val id: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "revision_id", nullable = false)
    val revisionId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val action: SupportActionType,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "action_payload_digest", nullable = false, length = 64)
    val actionPayloadDigest: String,
    @Column(name = "expected_target_version", nullable = false)
    val expectedTargetVersion: Long,
    @Column(name = "target_version_after", nullable = false)
    val targetVersionAfter: Long,
    @Column(name = "previous_target_state", nullable = false, length = 32)
    val previousTargetState: String,
    @Column(name = "current_target_state", nullable = false, length = 32)
    val currentTargetState: String,
    @Column(name = "previous_pickup_slot_id", nullable = false)
    val previousPickupSlotId: UUID,
    @Column(name = "current_pickup_slot_id", nullable = false)
    val currentPickupSlotId: UUID,
    @Column(name = "payment_recovery_state", length = 32)
    val paymentRecoveryState: String?,
    @Column(name = "authorization_id")
    val authorizationId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val outcome: SupportOrderChangeExecutionOutcome,
    @Column(name = "reason_code", nullable = false, length = 40)
    val reasonCode: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

@Entity
@Table(name = "support_order_change_authorization_use")
internal class SupportOrderChangeAuthorizationUseEntity(
    @Id
    @Column(name = "execution_id")
    val executionId: UUID,
    @Column(name = "authorization_id", nullable = false)
    val authorizationId: UUID,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "revision_number", nullable = false)
    val revisionNumber: Int,
    @Column(name = "action_payload_digest", nullable = false, length = 64)
    val actionPayloadDigest: String,
    @Column(name = "target_version", nullable = false)
    val targetVersion: Long,
    @Column(name = "used_at", nullable = false)
    val usedAt: Instant,
)

internal interface SupportOrderChangeAuthorizationJpaRepository : JpaRepository<SupportOrderChangeAuthorizationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from SupportOrderChangeAuthorizationEntity authorization where authorization.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportOrderChangeAuthorizationEntity?

    fun findByAuthorizedByActorIdAndIdempotencyKey(
        authorizedByActorId: UUID,
        idempotencyKey: String,
    ): SupportOrderChangeAuthorizationEntity?
}

internal interface SupportOrderChangeAuthorizationUseJpaRepository : JpaRepository<SupportOrderChangeAuthorizationUseEntity, UUID> {
    fun findByAuthorizationIdOrderByUsedAtAsc(authorizationId: UUID): List<SupportOrderChangeAuthorizationUseEntity>
}

internal interface SupportOrderChangeExecutionJpaRepository : JpaRepository<SupportOrderChangeExecutionEntity, UUID> {
    fun findByActorIdAndIdempotencyKey(
        actorId: UUID,
        idempotencyKey: String,
    ): SupportOrderChangeExecutionEntity?

    fun findByRequestId(requestId: UUID): SupportOrderChangeExecutionEntity?
}
