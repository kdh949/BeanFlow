package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ordering_order")
internal class OrderEntity(
	@Id
	val id: UUID,
	@Column(name = "customer_id", nullable = false)
	val customerId: UUID,
	@Column(name = "store_id", nullable = false)
	val storeId: UUID,
	@Column(name = "pickup_slot_id", nullable = false)
	val pickupSlotId: UUID,
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	var state: OrderState,
	@Column(name = "subtotal_krw", nullable = false)
	val subtotalKrw: Long,
	@Column(name = "coupon_discount_krw", nullable = false)
	val couponDiscountKrw: Long,
	@Column(name = "points_applied_krw", nullable = false)
	val pointsAppliedKrw: Long,
	@Column(name = "payable_krw", nullable = false)
	val payableKrw: Long,
	@Column(nullable = false)
	val currency: String = "KRW",
	@Column(name = "reservation_expires_at")
	val reservationExpiresAt: Instant?,
	@Column(name = "created_at", nullable = false)
	val createdAt: Instant,
	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,
	@Version
	var version: Long = 0,
)

@Entity
@Table(name = "ordering_order_line")
internal class OrderLineEntity(
	@Id
	val id: UUID,
	@Column(name = "order_id", nullable = false)
	val orderId: UUID,
	@Column(name = "line_sequence", nullable = false)
	val lineSequence: Int,
	@Column(name = "menu_id", nullable = false)
	val menuId: UUID,
	@Column(name = "menu_name", nullable = false)
	val menuName: String,
	@Column(name = "option_names_json", nullable = false, columnDefinition = "text")
	val optionNamesJson: String,
	@Column(name = "sellable_requirements_json", nullable = false, columnDefinition = "text")
	val sellableRequirementsJson: String,
	@Column(name = "unit_price_krw", nullable = false)
	val unitPriceKrw: Long,
	@Column(nullable = false)
	val quantity: Long,
	@Column(name = "gross_krw", nullable = false)
	val grossKrw: Long,
	@Column(name = "coupon_discount_krw", nullable = false)
	val couponDiscountKrw: Long,
	@Column(name = "points_applied_krw", nullable = false)
	val pointsAppliedKrw: Long,
	@Column(name = "cash_payable_krw", nullable = false)
	val cashPayableKrw: Long,
)

internal enum class IdempotencyStatus {
	PROCESSING,
	COMPLETED,
	FAILED,
	MANUAL_REVIEW,
}

@Entity
@Table(name = "ordering_idempotency_record")
internal class IdempotencyRecordEntity(
	@Id
	val id: UUID,
	@Column(name = "actor_id", nullable = false)
	val actorId: UUID,
	@Column(nullable = false)
	val operation: String,
	@Column(name = "idempotency_key", nullable = false)
	val idempotencyKey: String,
	@Column(name = "payload_hash", nullable = false, length = 64)
	val payloadHash: String,
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	var status: IdempotencyStatus,
	@Column(name = "intended_order_id", nullable = false)
	val intendedOrderId: UUID,
	@Column(name = "order_id")
	var orderId: UUID? = null,
	@Column(name = "response_status")
	var responseStatus: Int? = null,
	@Column(name = "response_body", columnDefinition = "text")
	var responseBody: String? = null,
	@Column(name = "response_version")
	var responseVersion: Int? = null,
	@Column(name = "started_at", nullable = false)
	val startedAt: Instant,
	@Column(name = "completed_at")
	var completedAt: Instant? = null,
	@Version
	var version: Long = 0,
)

internal interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select beanOrder from OrderEntity beanOrder where beanOrder.id = :id")
	fun findLockedById(@Param("id") id: UUID): OrderEntity?

	@Query(
		"select beanOrder.id from OrderEntity beanOrder " +
			"where beanOrder.state = io.github.kdh949.beanflow.ordering.internal.domain.OrderState.PENDING_PAYMENT " +
			"and beanOrder.reservationExpiresAt <= :now " +
			"order by beanOrder.reservationExpiresAt, beanOrder.id",
	)
	fun findDueIds(@Param("now") now: Instant, pageable: Pageable): List<UUID>
}

internal interface OrderLineJpaRepository : JpaRepository<OrderLineEntity, UUID> {
	fun findAllByOrderIdOrderByLineSequence(orderId: UUID): List<OrderLineEntity>
}

internal interface IdempotencyRecordJpaRepository : JpaRepository<IdempotencyRecordEntity, UUID> {
	fun findByActorIdAndOperationAndIdempotencyKey(
		actorId: UUID,
		operation: String,
		idempotencyKey: String,
	): IdempotencyRecordEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from IdempotencyRecordEntity record where record.id = :id")
	fun findLockedById(@Param("id") id: UUID): IdempotencyRecordEntity?
}
