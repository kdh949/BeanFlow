package io.github.kdh949.beanflow.loyalty.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal enum class PointRecoveryPendingState {
    PENDING,
    SETTLED,
}

@Entity
@Table(name = "loyalty_point_recovery_pending")
internal class PointRecoveryPendingEntity(
    @Id
    val id: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "refund_source_reference", nullable = false, length = 240)
    val refundSourceReference: String,
    @Column(name = "initial_amount_krw", nullable = false)
    val initialAmountKrw: Long,
    @Column(name = "remaining_amount_krw", nullable = false)
    var remainingAmountKrw: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PointRecoveryPendingState,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "settled_at")
    var settledAt: Instant? = null,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "loyalty_point_recovery_result")
internal class PointRecoveryResultEntity(
    @Id
    val id: UUID,
    @Column(name = "refund_id", nullable = false, unique = true)
    val refundId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "refund_source_reference", nullable = false, unique = true, length = 240)
    val refundSourceReference: String,
    @Column(name = "completion_source_reference", nullable = false, length = 240)
    val completionSourceReference: String,
    @Column(name = "completion_aggregate_version", nullable = false)
    val completionAggregateVersion: Long,
    @Column(name = "snapshot_schema_version", nullable = false)
    val snapshotSchemaVersion: Int,
    @Column(name = "snapshot_hash", nullable = false, length = 64)
    val snapshotHash: String,
    @Column(name = "target_amount_krw", nullable = false)
    val targetAmountKrw: Long,
    @Column(name = "recovered_amount_krw", nullable = false)
    val recoveredAmountKrw: Long,
    @Column(name = "pending_amount_krw", nullable = false)
    val pendingAmountKrw: Long,
    @Column(name = "refund_succeeded_at", nullable = false)
    val refundSucceededAt: Instant,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal enum class PointAccrualResultState {
    LEGACY_NOT_APPLICABLE,
    APPLIED,
    NO_ACCRUAL,
}

@Entity
@Table(name = "loyalty_point_accrual_result")
internal class PointAccrualResultEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false, unique = true)
    val orderId: UUID,
    @Column(name = "point_account_id")
    val pointAccountId: UUID?,
    @Column(name = "completion_source_reference", nullable = false, unique = true, length = 240)
    val completionSourceReference: String,
    @Column(name = "completion_aggregate_version", nullable = false)
    val completionAggregateVersion: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false)
    val sourceState: PointAccrualResultState,
    @Column(name = "snapshot_schema_version")
    val snapshotSchemaVersion: Int?,
    @Column(name = "snapshot_hash", length = 64)
    val snapshotHash: String?,
    @Column(name = "excluded_units_hash", length = 64)
    val excludedUnitsHash: String?,
    @Column(name = "snapshot_gross_amount_krw")
    val snapshotGrossAmountKrw: Long?,
    @Column(name = "excluded_amount_krw")
    val excludedAmountKrw: Long?,
    @Column(name = "accrued_amount_krw")
    val accruedAmountKrw: Long?,
    @Column(name = "offset_amount_krw")
    val offsetAmountKrw: Long?,
    @Column(name = "available_amount_krw")
    val availableAmountKrw: Long?,
    @Column(name = "point_lot_id")
    val pointLotId: UUID?,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal interface PointRecoveryPendingJpaRepository : JpaRepository<PointRecoveryPendingEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select pending from PointRecoveryPendingEntity pending " +
            "where pending.pointAccountId = :accountId and pending.state = " +
            "io.github.kdh949.beanflow.loyalty.internal.PointRecoveryPendingState.PENDING " +
            "order by pending.createdAt, pending.id",
    )
    fun findPendingLocked(
        @Param("accountId") accountId: UUID,
    ): List<PointRecoveryPendingEntity>
}

internal interface PointRecoveryResultJpaRepository : JpaRepository<PointRecoveryResultEntity, UUID> {
    fun findByRefundId(refundId: UUID): PointRecoveryResultEntity?
}

internal interface PointAccrualResultJpaRepository : JpaRepository<PointAccrualResultEntity, UUID> {
    fun findByOrderId(orderId: UUID): PointAccrualResultEntity?
}
