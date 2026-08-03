package io.github.kdh949.beanflow.loyalty.internal

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class PointAdjustmentOperation {
    POINT_ADJUSTMENT,
}

@Entity
@Immutable
@Table(name = "loyalty_point_adjustment_command_idempotency")
internal class PointAdjustmentIdempotencyEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val operation: PointAdjustmentOperation,
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

internal interface PointAdjustmentIdempotencyJpaRepository : JpaRepository<PointAdjustmentIdempotencyEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select record from PointAdjustmentIdempotencyEntity record " +
            "where record.actorId = :actorId and record.operation = :operation " +
            "and record.idempotencyKey = :idempotencyKey",
    )
    fun findLockedByScope(
        @Param("actorId") actorId: UUID,
        @Param("operation") operation: PointAdjustmentOperation,
        @Param("idempotencyKey") idempotencyKey: String,
    ): PointAdjustmentIdempotencyEntity?

    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: PointAdjustmentOperation,
        idempotencyKey: String,
    ): PointAdjustmentIdempotencyEntity?

    @Query(
        "select record.id from PointAdjustmentIdempotencyEntity record " +
            "where record.retentionExpiresAt <= :now order by record.retentionExpiresAt, record.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

internal data class PointAdjustmentRetentionPurgeResult(
    val deletedCount: Int,
    val oldestDueAt: Instant?,
)

@Service
internal class PointAdjustmentIdempotencyRetentionService(
    private val records: PointAdjustmentIdempotencyJpaRepository,
) {
    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): PointAdjustmentRetentionPurgeResult {
        require(chunkSize in 1..MAX_CHUNK_SIZE)
        val ids = records.findDueIds(now, PageRequest.of(0, chunkSize))
        val oldestDueAt = records.findAllById(ids).minOfOrNull(PointAdjustmentIdempotencyEntity::retentionExpiresAt)
        if (ids.isNotEmpty()) records.deleteAllByIdInBatch(ids)
        return PointAdjustmentRetentionPurgeResult(ids.size, oldestDueAt)
    }

    private companion object {
        const val MAX_CHUNK_SIZE = 100
    }
}

internal enum class PointAdjustmentRetentionOutcome {
    SUCCEEDED,
    FAILED,
}

internal data class PointAdjustmentRetentionRunResult(
    val outcome: PointAdjustmentRetentionOutcome,
    val deletedCount: Int?,
)

@Component
internal class LoyaltyPointAdjustmentIdempotencyRetentionWorker(
    private val retention: PointAdjustmentIdempotencyRetentionService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.loyalty-point-adjustment-retention.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(chunkSize in 1..100) { "Point adjustment retention chunk size must be between 1 and 100" }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.loyalty-point-adjustment-retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.loyalty-point-adjustment-retention.initial-delay-ms:300000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): PointAdjustmentRetentionRunResult {
        val now = clock.instant()
        return try {
            val result = retention.purgeDue(now, chunkSize)
            metric(PointAdjustmentRetentionOutcome.SUCCEEDED)
            result.oldestDueAt?.let { oldest ->
                meterRegistry
                    .summary("beanflow.loyalty.point_adjustment.idempotency_retention.oldest_due_age.seconds")
                    .record(Duration.between(oldest, now).seconds.coerceAtLeast(0).toDouble())
            }
            if (result.deletedCount > 0) {
                logger.info(
                    "loyalty_point_adjustment_retention outcome=SUCCEEDED deletedCount={} oldestDueAgeSeconds={}",
                    result.deletedCount,
                    result.oldestDueAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) },
                )
            }
            PointAdjustmentRetentionRunResult(PointAdjustmentRetentionOutcome.SUCCEEDED, result.deletedCount)
        } catch (failure: RuntimeException) {
            metric(PointAdjustmentRetentionOutcome.FAILED)
            logger.error(
                "loyalty_point_adjustment_retention outcome=FAILED failureType={}",
                failure.javaClass.simpleName,
            )
            PointAdjustmentRetentionRunResult(PointAdjustmentRetentionOutcome.FAILED, null)
        }
    }

    private fun metric(outcome: PointAdjustmentRetentionOutcome) {
        meterRegistry
            .counter(
                "beanflow.loyalty.point_adjustment.idempotency_retention.count",
                "outcome",
                outcome.name,
            ).increment()
    }
}
