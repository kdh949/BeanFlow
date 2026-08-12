package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import io.github.kdh949.beanflow.support.internal.domain.ProfileChangePurpose
import io.github.kdh949.beanflow.support.internal.domain.ProfileRiskClass
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChange
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal enum class ProfileChangeSubjectType {
    CUSTOMER,
    STORE,
    RIDER,
}

@Entity
@Table(name = "support_profile_change")
internal class SupportProfileChangeEntity(
    @Id val id: UUID,
    @Column(name = "support_case_id", nullable = false) val supportCaseId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false) val subjectType: ProfileChangeSubjectType,
    @Column(name = "subject_id", nullable = false) val subjectId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val purpose: ProfileChangePurpose,
    @Enumerated(EnumType.STRING) @Column(name = "risk_class", nullable = false) val riskClass: ProfileRiskClass,
    @Column(name = "requester_actor_id", nullable = false) val requesterActorId: UUID,
    @Column(name = "executor_actor_id", nullable = false) var executorActorId: UUID,
    @Column(name = "verification_session_id", nullable = false) var verificationSessionId: UUID,
    @Column(name = "expected_profile_version", nullable = false) var expectedProfileVersion: Long,
    @Column(name = "current_profile_version") var currentProfileVersion: Long?,
    @Column(name = "payload_digest", nullable = false, length = 64) var payloadDigest: String,
    @Column(name = "action_request_id") val actionRequestId: UUID?,
    @Column(name = "owner_change_id") var ownerChangeId: UUID?,
    @Column(name = "masked_before", length = 1000) var maskedBefore: String?,
    @Column(name = "masked_after", length = 1000) var maskedAfter: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: SupportProfileChangeState,
    @Enumerated(EnumType.STRING) @Column(name = "notification_state", nullable = false)
    var notificationState: SupportProfileNotificationState,
    @Column(name = "notification_failure_code", length = 80) var notificationFailureCode: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(nullable = false) var version: Long,
) {
    fun toAggregate(): SupportProfileChange =
        SupportProfileChange.restore(
            id,
            supportCaseId,
            subjectId,
            purpose,
            requesterActorId,
            executorActorId,
            verificationSessionId,
            expectedProfileVersion,
            payloadDigest,
            actionRequestId,
            ownerChangeId,
            currentProfileVersion,
            maskedBefore,
            maskedAfter,
            state,
            notificationState,
            notificationFailureCode,
            createdAt,
            updatedAt,
            version,
        )

    fun apply(aggregate: SupportProfileChange) {
        executorActorId = aggregate.executorActorId
        verificationSessionId = aggregate.verificationSessionId
        expectedProfileVersion = aggregate.expectedProfileVersion
        payloadDigest = aggregate.payloadDigest
        currentProfileVersion = aggregate.currentProfileVersion
        ownerChangeId = aggregate.ownerChangeId
        maskedBefore = aggregate.maskedBefore
        maskedAfter = aggregate.maskedAfter
        state = aggregate.state
        notificationState = aggregate.notificationState
        notificationFailureCode = aggregate.notificationFailureCode
        updatedAt = aggregate.updatedAt
        version = aggregate.version
    }
}

@Entity
@Table(name = "support_profile_change_notification")
internal class SupportProfileChangeNotificationEntity(
    @Id val id: UUID,
    @Column(name = "profile_change_id", nullable = false) val profileChangeId: UUID,
    @Column(name = "owner_target_id", nullable = false) val ownerTargetId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "target_kind", nullable = false) val targetKind: ProfileNotificationTargetKind,
    @Enumerated(EnumType.STRING) @Column(name = "channel_type", nullable = false) val channelType: ProfileNotificationChannel,
    @Column(name = "delivery_id") var deliveryId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: SupportProfileNotificationState,
    @Column(name = "failure_code", length = 80) var failureCode: String?,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int,
    @Column(name = "source_occurred_at", nullable = false) val sourceOccurredAt: Instant,
    @Column(name = "source_correlation_id", nullable = false, length = 128) val sourceCorrelationId: String,
    @Column(name = "claim_id") var claimId: UUID?,
    @Column(name = "claim_expires_at") var claimExpiresAt: Instant?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "support_profile_change_idempotency")
internal class SupportProfileChangeIdempotencyEntity(
    @Id val id: UUID,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 80) val operation: String,
    @Column(name = "idempotency_key", nullable = false, length = 128) val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64) val payloadHash: String,
    @Column(name = "profile_change_id", nullable = false) val profileChangeId: UUID,
    @Column(name = "response_status", nullable = false) val responseStatus: Int,
    @Column(name = "response_body", nullable = false, columnDefinition = "text") val responseBody: String,
    @Column(name = "failure_code", length = 64) val failureCode: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false) val retentionExpiresAt: Instant,
)

internal interface SupportProfileChangeJpaRepository : JpaRepository<SupportProfileChangeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select change from SupportProfileChangeEntity change where change.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportProfileChangeEntity?

    fun findByActionRequestId(actionRequestId: UUID): SupportProfileChangeEntity?
}

internal interface SupportProfileChangeNotificationJpaRepository : JpaRepository<SupportProfileChangeNotificationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select target from SupportProfileChangeNotificationEntity target where target.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SupportProfileChangeNotificationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select target from SupportProfileChangeNotificationEntity target where target.profileChangeId = :profileChangeId order by target.id",
    )
    fun findLockedByProfileChangeId(
        @Param("profileChangeId") profileChangeId: UUID,
    ): List<SupportProfileChangeNotificationEntity>

    fun findByProfileChangeIdOrderById(profileChangeId: UUID): List<SupportProfileChangeNotificationEntity>

    @Query(
        """
        select distinct target.profileChangeId
          from SupportProfileChangeNotificationEntity target
         where target.state = io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState.PENDING
            or (target.state = io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState.PROCESSING
                and target.claimExpiresAt <= :now)
         order by target.profileChangeId
        """,
    )
    fun findRecoverableProfileChangeIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

internal interface SupportProfileChangeIdempotencyJpaRepository : JpaRepository<SupportProfileChangeIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): SupportProfileChangeIdempotencyEntity?
}
