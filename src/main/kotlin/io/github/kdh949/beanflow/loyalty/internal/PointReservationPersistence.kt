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
	@Version
	var version: Long = 0,
)

internal enum class PointReservationState {
	RESERVED,
	USED,
	RELEASED,
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
)

internal interface PointAccountJpaRepository : JpaRepository<PointAccountEntity, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from PointAccountEntity account where account.customerId = :customerId")
	fun findLockedByCustomerId(@Param("customerId") customerId: UUID): PointAccountEntity?
}

internal interface PointLotJpaRepository : JpaRepository<PointLotEntity, UUID> {
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
	fun findAllLockedByIds(@Param("ids") ids: Collection<UUID>): List<PointLotEntity>
}

internal interface PointReservationJpaRepository : JpaRepository<PointReservationEntity, UUID> {
	fun findBySourceReference(sourceReference: String): PointReservationEntity?
	fun findByOrderId(orderId: UUID): PointReservationEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select reservation from PointReservationEntity reservation where reservation.orderId = :orderId")
	fun findLockedByOrderId(@Param("orderId") orderId: UUID): PointReservationEntity?
}

internal interface PointReservationAllocationJpaRepository :
	JpaRepository<PointReservationAllocationEntity, UUID> {
	fun findAllByPointReservationIdOrderByPointLotId(pointReservationId: UUID): List<PointReservationAllocationEntity>
}

internal interface PointTransactionJpaRepository : JpaRepository<PointTransactionEntity, UUID>
