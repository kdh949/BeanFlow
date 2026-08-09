package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Duration
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
    state: OrderState,
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
    reservationExpiresAt: Instant?,
    paidAtAtCreation: Instant? = null,
    acceptanceWarningAtAtCreation: Instant? = null,
    acceptanceDeadlineAtAtCreation: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    updatedAt: Instant,
    @Version
    var version: Long = 0,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: OrderState = state
        protected set

    @Column(name = "reservation_expires_at")
    var reservationExpiresAt: Instant? = reservationExpiresAt
        protected set

    @Column(name = "paid_at")
    var paidAt: Instant? = paidAtAtCreation
        protected set

    @Column(name = "acceptance_warning_at")
    var acceptanceWarningAt: Instant? = acceptanceWarningAtAtCreation
        protected set

    @Column(name = "acceptance_warning_requested_at")
    var acceptanceWarningRequestedAt: Instant? = null
        protected set

    @Column(name = "acceptance_deadline_at")
    var acceptanceDeadlineAt: Instant? = acceptanceDeadlineAtAtCreation
        protected set

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null
        protected set

    @Column(name = "rejected_at")
    var rejectedAt: Instant? = null
        protected set

    @Column(name = "preparing_at")
    var preparingAt: Instant? = null
        protected set

    @Column(name = "ready_at")
    var readyAt: Instant? = null
        protected set

    @Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_cause", length = 32)
    var cancellationCause: OrderCancellationCause? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason_code", length = 32)
    var cancellationReasonCode: CustomerCancellationReasonCode? = null
        protected set

    @Column(name = "cancellation_detail", length = 200)
    var cancellationDetail: String? = null
        protected set

    @Column(name = "rejection_reason", length = 500)
    var rejectionReason: String? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        protected set

    fun markPaid(now: Instant) {
        requireState(OrderState.PENDING_PAYMENT, "Only a pending-payment order can be paid")
        state = OrderState.PAID
        reservationExpiresAt = null
        paidAt = now
        acceptanceWarningAt = now.plus(ACCEPTANCE_WARNING_DELAY)
        acceptanceDeadlineAt = now.plus(ACCEPTANCE_DEADLINE_DELAY)
        updatedAt = now
    }

    fun accept(now: Instant) {
        requireState(OrderState.PAID, "Only a paid order can be accepted")
        val deadline =
            requireNotNull(acceptanceDeadlineAt) {
                "Paid order has no acceptance deadline"
            }
        if (!now.isBefore(deadline)) {
            conflict("Store acceptance deadline has passed")
        }
        state = OrderState.ACCEPTED
        acceptedAt = now
        updatedAt = now
    }

    fun reject(
        now: Instant,
        reason: String,
    ) {
        requireState(OrderState.PAID, "Only a paid order can be rejected")
        val normalizedReason = reason.trim()
        if (normalizedReason.isEmpty() || normalizedReason.length > 500) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Rejection reason must contain between 1 and 500 characters",
            )
        }
        state = OrderState.REJECTED
        rejectedAt = now
        rejectionReason = normalizedReason
        updatedAt = now
    }

    fun startPreparing(now: Instant) {
        requireState(OrderState.ACCEPTED, "Only an accepted order can start preparation")
        state = OrderState.PREPARING
        preparingAt = now
        updatedAt = now
    }

    fun markReady(now: Instant) {
        requireState(OrderState.PREPARING, "Only a preparing order can become ready")
        state = OrderState.READY
        readyAt = now
        updatedAt = now
    }

    fun complete(now: Instant) {
        requireState(OrderState.READY, "Only a ready order can be completed")
        state = OrderState.COMPLETED
        completedAt = now
        updatedAt = now
    }

    fun markAcceptanceWarningRequested(now: Instant): Boolean {
        if (state != OrderState.PAID || acceptanceWarningRequestedAt != null) {
            return false
        }
        val warningAt = acceptanceWarningAt ?: return false
        if (now.isBefore(warningAt)) {
            return false
        }
        acceptanceWarningRequestedAt = now
        updatedAt = now
        return true
    }

    fun expire(now: Instant) {
        requireState(OrderState.PENDING_PAYMENT, "Only a pending-payment order can expire")
        state = OrderState.EXPIRED
        updatedAt = now
    }

    fun cancelAfterPaymentDeclined(now: Instant) {
        requireState(OrderState.PENDING_PAYMENT, "Only a pending-payment order can be cancelled")
        state = OrderState.CANCELLED
        reservationExpiresAt = null
        cancelledAt = now
        cancellationCause = OrderCancellationCause.PAYMENT_DECLINED
        updatedAt = now
    }

    fun cancelByCustomer(
        now: Instant,
        reasonCode: CustomerCancellationReasonCode,
        detail: String?,
    ) {
        if (state != OrderState.PENDING_PAYMENT && state != OrderState.PAID) {
            conflict("Order state does not allow customer cancellation")
        }
        if (state == OrderState.PAID) {
            val deadline =
                acceptanceDeadlineAt
                    ?: throw DomainFailure(
                        FailureCode.DEPENDENCY_UNAVAILABLE,
                        "Paid order has no acceptance deadline",
                    )
            if (!now.isBefore(deadline)) {
                conflict("Store acceptance deadline has passed")
            }
        }
        val normalizedDetail = CanonicalCustomerCancellationPayload.normalizeDetail(detail)
        state = OrderState.CANCELLED
        reservationExpiresAt = null
        cancelledAt = now
        cancellationCause = OrderCancellationCause.CUSTOMER_REQUEST
        cancellationReasonCode = reasonCode
        cancellationDetail = normalizedDetail
        updatedAt = now
    }

    private fun requireState(
        expected: OrderState,
        message: String,
    ) {
        if (state != expected) {
            conflict(message)
        }
    }

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private companion object {
        val ACCEPTANCE_WARNING_DELAY: Duration = Duration.ofMinutes(2)
        val ACCEPTANCE_DEADLINE_DELAY: Duration = Duration.ofMinutes(3)
    }
}

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
    @Enumerated(EnumType.STRING)
    @Column(name = "option_selection_snapshot_state", nullable = false, length = 32)
    val optionSelectionSnapshotState: OptionSelectionSnapshotState,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_option_ids_json", columnDefinition = "jsonb")
    val normalizedOptionIds: List<UUID>?,
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
) {
    init {
        when (optionSelectionSnapshotState) {
            OptionSelectionSnapshotState.LEGACY_UNAVAILABLE -> {
                require(normalizedOptionIds == null) {
                    "Legacy option selection must not contain inferred option IDs"
                }
            }

            OptionSelectionSnapshotState.SNAPSHOTTED -> {
                val snapshot = requireNotNull(normalizedOptionIds) { "Snapshotted option selection requires option IDs" }
                require(snapshot == snapshot.distinct().sortedBy { it.toString() }) {
                    "Snapshotted option IDs must be sorted and unique"
                }
            }
        }
    }
}

internal enum class OptionSelectionSnapshotState {
    LEGACY_UNAVAILABLE,
    SNAPSHOTTED,
}

internal enum class IdempotencyStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    MANUAL_REVIEW,
}

internal enum class IdempotencyManualReviewReason {
    ORDER_FOUND,
    ORDER_NOT_FOUND,
    LEGACY_UNSPECIFIED,
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
    @Column(name = "retention_expires_at")
    var retentionExpiresAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_review_reason")
    var manualReviewReason: IdempotencyManualReviewReason? = null,
    @Column(name = "manual_review_started_at")
    var manualReviewStartedAt: Instant? = null,
    @Column(name = "intended_order_exists")
    var intendedOrderExists: Boolean? = null,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "ordering_store_command_idempotency")
internal class StoreCommandIdempotencyEntity(
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
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select beanOrder from OrderEntity beanOrder where beanOrder.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): OrderEntity?

    @Query(
        "select beanOrder.id from OrderEntity beanOrder " +
            "where beanOrder.state = io.github.kdh949.beanflow.ordering.internal.domain.OrderState.PENDING_PAYMENT " +
            "and beanOrder.reservationExpiresAt <= :now " +
            "order by beanOrder.reservationExpiresAt, beanOrder.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Query(
        "select beanOrder.id from OrderEntity beanOrder " +
            "where beanOrder.state = io.github.kdh949.beanflow.ordering.internal.domain.OrderState.PAID " +
            "and beanOrder.acceptanceWarningAt <= :now " +
            "and beanOrder.acceptanceWarningRequestedAt is null " +
            "order by beanOrder.acceptanceWarningAt, beanOrder.id",
    )
    fun findAcceptanceWarningDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Query(
        "select beanOrder.id from OrderEntity beanOrder " +
            "where beanOrder.state = io.github.kdh949.beanflow.ordering.internal.domain.OrderState.PAID " +
            "and beanOrder.acceptanceDeadlineAt <= :now " +
            "order by beanOrder.acceptanceDeadlineAt, beanOrder.id",
    )
    fun findAcceptanceTimeoutDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
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
    fun findLockedById(
        @Param("id") id: UUID,
    ): IdempotencyRecordEntity?

    fun countByStatusAndStartedAtBefore(
        status: IdempotencyStatus,
        startedAt: Instant,
    ): Long

    @Query(
        "select record.id from IdempotencyRecordEntity record " +
            "where record.retentionExpiresAt <= :now order by record.retentionExpiresAt, record.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    fun countByRetentionExpiresAtLessThanEqual(now: Instant): Long

    @Query(
        "select record.id from IdempotencyRecordEntity record " +
            "where record.status = io.github.kdh949.beanflow.ordering.internal.IdempotencyStatus.PROCESSING " +
            "and record.startedAt <= :cutoff order by record.startedAt, record.id",
    )
    fun findStuckProcessingIds(
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<UUID>
}

internal interface StoreCommandIdempotencyJpaRepository : JpaRepository<StoreCommandIdempotencyEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): StoreCommandIdempotencyEntity?

    @Query(
        "select record.id from StoreCommandIdempotencyEntity record " +
            "where record.retentionExpiresAt <= :now order by record.retentionExpiresAt, record.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    fun countByRetentionExpiresAtLessThanEqual(now: Instant): Long
}
