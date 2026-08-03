package io.github.kdh949.beanflow.ordering.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ordering_cancellation_command_idempotency")
internal class CancellationCommandIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(nullable = false, length = 80)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,
    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    val responseBody: String,
    @Column(name = "response_version", nullable = false)
    val responseVersion: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface CancellationCommandIdempotencyJpaRepository : JpaRepository<CancellationCommandIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): CancellationCommandIdempotencyEntity?

    @Query(
        "select record.id from CancellationCommandIdempotencyEntity record " +
            "where record.retentionExpiresAt <= :now order by record.retentionExpiresAt, record.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

internal enum class AcceptanceTimeoutWorkState {
    PENDING,
    CLAIMED,
    COMPLETED,
    MANUAL_REVIEW,
}

internal enum class AcceptanceTimeoutCompletionOutcome {
    REJECTED,
    NOT_APPLICABLE,
}

@Entity
@Table(name = "ordering_acceptance_timeout_work")
internal class AcceptanceTimeoutWorkEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "acceptance_deadline_at", nullable = false)
    val acceptanceDeadlineAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: AcceptanceTimeoutWorkState,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = createdAt,
    @Enumerated(EnumType.STRING)
    @Column(name = "completion_outcome")
    var completionOutcome: AcceptanceTimeoutCompletionOutcome? = null,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "claim_until")
    var claimUntil: Instant? = null,
    @Column(name = "last_failure_code")
    var lastFailureCode: String? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "retention_expires_at")
    var retentionExpiresAt: Instant? = null,
    @Version
    var version: Long = 0,
)

internal interface AcceptanceTimeoutWorkJpaRepository : JpaRepository<AcceptanceTimeoutWorkEntity, UUID> {
    fun findByOrderIdAndAcceptanceDeadlineAt(
        orderId: UUID,
        acceptanceDeadlineAt: Instant,
    ): AcceptanceTimeoutWorkEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select work from AcceptanceTimeoutWorkEntity work where work.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): AcceptanceTimeoutWorkEntity?

    @Query(
        "select work.id from AcceptanceTimeoutWorkEntity work " +
            "where (work.state = io.github.kdh949.beanflow.ordering.internal.AcceptanceTimeoutWorkState.PENDING " +
            "and work.nextAttemptAt <= :now) or " +
            "(work.state = io.github.kdh949.beanflow.ordering.internal.AcceptanceTimeoutWorkState.CLAIMED " +
            "and work.claimUntil <= :now) order by work.nextAttemptAt, work.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Query(
        "select work.id from AcceptanceTimeoutWorkEntity work " +
            "where work.state = io.github.kdh949.beanflow.ordering.internal.AcceptanceTimeoutWorkState.COMPLETED " +
            "and work.retentionExpiresAt <= :now order by work.retentionExpiresAt, work.id",
    )
    fun findRetentionDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}
