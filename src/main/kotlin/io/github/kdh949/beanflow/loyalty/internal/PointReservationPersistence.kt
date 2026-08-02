package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
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

@Entity
@Table(name = "loyalty_point_account")
internal class PointAccountEntity(
    @Id
    val id: UUID,
    @Column(name = "customer_id", nullable = false, unique = true)
    val customerId: UUID,
    @Column(name = "available_points_krw", nullable = false)
    var availablePointsKrw: Long,
    @Column(name = "reserved_points_krw", nullable = false)
    var reservedPointsKrw: Long = 0,
    @Column(name = "recovery_pending_krw", nullable = false)
    var recoveryPendingKrw: Long = 0,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "loyalty_point_lot")
internal class PointLotEntity(
    @Id
    val id: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "available_amount_krw", nullable = false)
    var availableAmountKrw: Long,
    @Column(name = "reserved_amount_krw", nullable = false)
    var reservedAmountKrw: Long = 0,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type", nullable = false, updatable = false)
    val issuerType: PointIssuerType,
    @Column(name = "issuer_reference", nullable = false, length = 240, updatable = false)
    val issuerReference: String,
    @Column(name = "original_point_lot_id")
    val originalPointLotId: UUID? = null,
    @Column(name = "compensation_source_reference", length = 240)
    val compensationSourceReference: String? = null,
    @Column(name = "restoration_trigger", length = 32)
    val restorationTrigger: String? = null,
    @Column(name = "restoration_policy_version_id")
    val restorationPolicyVersionId: Long? = null,
    @Column(name = "restoration_refund_id")
    val restorationRefundId: UUID? = null,
    @Column(name = "accrual_order_id")
    val accrualOrderId: UUID? = null,
    @Column(name = "accrual_source_reference", length = 240)
    val accrualSourceReference: String? = null,
    @Column(name = "accrual_snapshot_hash", length = 64)
    val accrualSnapshotHash: String? = null,
    @Version
    var version: Long = 0,
) {
    init {
        require(issuerReference.isNotBlank()) { "Point issuer reference must not be blank" }
    }
}

internal enum class PointReservationState {
    RESERVED,
    USED,
    RELEASED,
    RESTORED,
}

@Entity
@Table(name = "loyalty_point_reservation")
internal class PointReservationEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PointReservationState,
    @Column(name = "reservation_expires_at", nullable = false)
    val reservationExpiresAt: Instant,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "restoration_source_reference", length = 240)
    var restorationSourceReference: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "restoration_trigger")
    var restorationTrigger: OrderTerminationTrigger? = null,
    @Column(name = "restoration_policy_version_id")
    var restorationPolicyVersionId: Long? = null,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "loyalty_point_reservation_allocation")
internal class PointReservationAllocationEntity(
    @Id
    val id: UUID,
    @Column(name = "point_reservation_id", nullable = false)
    val pointReservationId: UUID,
    @Column(name = "point_lot_id", nullable = false)
    val pointLotId: UUID,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
)

internal enum class PointTransactionType {
    USE,
    EXPIRATION,
    RESTORE,
    COMPENSATION,
    RESTORE_SKIPPED_EXPIRED,
    ACCRUAL,
    RECOVERY,
}

@Entity
@Table(name = "loyalty_point_transaction")
internal class PointTransactionEntity(
    @Id
    val id: UUID,
    @Column(name = "point_account_id", nullable = false)
    val pointAccountId: UUID,
    @Column(name = "point_lot_id", nullable = false)
    val pointLotId: UUID,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: PointTransactionType,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "refund_id")
    val refundId: UUID? = null,
    @Column(name = "order_line_id")
    val orderLineId: UUID? = null,
    @Column(name = "point_reservation_allocation_id")
    val pointReservationAllocationId: UUID? = null,
    @Column(name = "restoration_trigger", length = 32)
    val restorationTrigger: String? = null,
    @Column(name = "restoration_policy_version_id")
    val restorationPolicyVersionId: Long? = null,
    @Column(name = "restoration_disposition", length = 32)
    val restorationDisposition: String? = null,
    @Column(name = "point_recovery_pending_id")
    val pointRecoveryPendingId: UUID? = null,
)

internal enum class PartialRefundRestorationDisposition {
    ORIGINAL_LOT,
    COMPENSATION_LOT,
    SKIPPED_EXPIRED,
}

@Entity
@Table(name = "loyalty_partial_refund_restoration")
internal class PartialRefundRestorationEntity(
    @Id
    val id: UUID,
    @Column(name = "refund_id", nullable = false)
    val refundId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "order_line_id", nullable = false)
    val orderLineId: UUID,
    @Column(name = "point_reservation_id", nullable = false)
    val pointReservationId: UUID,
    @Column(name = "point_reservation_allocation_id", nullable = false)
    val pointReservationAllocationId: UUID,
    @Column(name = "original_point_lot_id", nullable = false)
    val originalPointLotId: UUID,
    @Column(name = "restored_point_lot_id", nullable = false)
    val restoredPointLotId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type", nullable = false)
    val issuerType: PointIssuerType,
    @Column(name = "issuer_reference", nullable = false)
    val issuerReference: String,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val disposition: PartialRefundRestorationDisposition,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: Long,
    @Column(name = "policy_mode", nullable = false)
    val policyMode: String,
    @Column(name = "policy_validity_days", nullable = false)
    val policyValidityDays: Int,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "restored_at", nullable = false)
    val restoredAt: Instant,
)

internal interface PointAccountJpaRepository : JpaRepository<PointAccountEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PointAccountEntity account where account.customerId = :customerId")
    fun findLockedByCustomerId(
        @Param("customerId") customerId: UUID,
    ): PointAccountEntity?
}

internal interface PointLotJpaRepository : JpaRepository<PointLotEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select lot from PointLotEntity lot where lot.pointAccountId = :accountId " +
            "and lot.availableAmountKrw > 0 order by lot.expiresAt, lot.id",
    )
    fun findRecoverableLotsLocked(
        @Param("accountId") accountId: UUID,
    ): List<PointLotEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select lot from PointLotEntity lot where lot.pointAccountId = :accountId " +
            "and lot.expiresAt > :now and lot.availableAmountKrw > 0 order by lot.expiresAt, lot.id",
    )
    fun findReservableLotsLocked(
        @Param("accountId") accountId: UUID,
        @Param("now") now: Instant,
    ): List<PointLotEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lot from PointLotEntity lot where lot.id in :ids order by lot.expiresAt, lot.id")
    fun findAllLockedByIds(
        @Param("ids") ids: Collection<UUID>,
    ): List<PointLotEntity>
}

internal interface PointReservationJpaRepository : JpaRepository<PointReservationEntity, UUID> {
    fun findBySourceReference(sourceReference: String): PointReservationEntity?

    fun findByOrderId(orderId: UUID): PointReservationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from PointReservationEntity reservation where reservation.orderId = :orderId")
    fun findLockedByOrderId(
        @Param("orderId") orderId: UUID,
    ): PointReservationEntity?
}

internal interface PointReservationAllocationJpaRepository : JpaRepository<PointReservationAllocationEntity, UUID> {
    fun findAllByPointReservationIdOrderByPointLotId(pointReservationId: UUID): List<PointReservationAllocationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select allocation from PointReservationAllocationEntity allocation " +
            "where allocation.pointReservationId = :reservationId order by allocation.pointLotId, allocation.id",
    )
    fun findAllLockedByReservationId(
        @Param("reservationId") reservationId: UUID,
    ): List<PointReservationAllocationEntity>
}

internal interface PointTransactionJpaRepository : JpaRepository<PointTransactionEntity, UUID>

internal interface PartialRefundRestorationJpaRepository : JpaRepository<PartialRefundRestorationEntity, UUID> {
    fun findAllByRefundIdOrderByOrderLineIdAscPointReservationAllocationIdAsc(refundId: UUID): List<PartialRefundRestorationEntity>

    @Query(
        "select coalesce(sum(restoration.amountKrw), 0) from PartialRefundRestorationEntity restoration " +
            "where restoration.pointReservationAllocationId = :allocationId",
    )
    fun sumRestoredAmountByAllocationId(
        @Param("allocationId") allocationId: UUID,
    ): Long
}
