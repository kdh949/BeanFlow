package io.github.kdh949.beanflow.fulfillment.internal

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
@Table(name = "fulfillment_pickup_slot")
internal class PickupSlotEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "starts_at", nullable = false)
    val startsAt: Instant,
    @Column(name = "ends_at", nullable = false)
    val endsAt: Instant,
    @Column(nullable = false)
    val capacity: Long,
    @Column(name = "reserved_count", nullable = false)
    var reservedCount: Long = 0,
    @Column(name = "confirmed_count", nullable = false)
    var confirmedCount: Long = 0,
    @Version
    var version: Long = 0,
) {
    fun reserveOne() {
        if (reservedCount + confirmedCount >= capacity) {
            throw IllegalStateException("Pickup slot capacity is exhausted")
        }
        reservedCount++
    }

    fun confirmOne() {
        check(reservedCount > 0)
        reservedCount--
        confirmedCount++
    }

    fun reserveConfirmedOne() {
        if (reservedCount + confirmedCount >= capacity) {
            throw IllegalStateException("Pickup slot capacity is exhausted")
        }
        confirmedCount++
    }

    fun releaseOne() {
        check(reservedCount > 0)
        reservedCount--
    }

    fun releaseConfirmedOne() {
        check(confirmedCount > 0)
        confirmedCount--
    }
}

internal enum class PickupReservationState {
    RESERVED,
    CONFIRMED,
    EXPIRED,
    RELEASED,
    RELEASED_AFTER_TERMINATION,
}

@Entity
@Table(name = "fulfillment_pickup_reservation")
internal class PickupReservationEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "slot_id", nullable = false)
    var slotId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PickupReservationState,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "slot_starts_at_snapshot", nullable = false)
    val slotStartsAtSnapshot: Instant,
    @Column(name = "slot_ends_at_snapshot", nullable = false)
    val slotEndsAtSnapshot: Instant,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "restoration_source_reference", length = 200)
    var restorationSourceReference: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "restoration_trigger")
    var restorationTrigger: OrderTerminationTrigger? = null,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "fulfillment_pickup_reschedule_history")
internal class PickupRescheduleHistoryEntity(
    @Id
    val id: UUID,
    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "previous_slot_id", nullable = false)
    val previousSlotId: UUID,
    @Column(name = "current_slot_id", nullable = false)
    val currentSlotId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_state", nullable = false, length = 32)
    val reservationState: PickupReservationState,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)

internal interface PickupSlotJpaRepository : JpaRepository<PickupSlotEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from PickupSlotEntity slot where slot.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): PickupSlotEntity?
}

internal interface PickupReservationJpaRepository : JpaRepository<PickupReservationEntity, UUID> {
    fun findByOrderId(orderId: UUID): PickupReservationEntity?

    fun findBySourceReference(sourceReference: String): PickupReservationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from PickupReservationEntity reservation where reservation.orderId = :orderId")
    fun findLockedByOrderId(
        @Param("orderId") orderId: UUID,
    ): PickupReservationEntity?
}

internal interface PickupRescheduleHistoryJpaRepository : JpaRepository<PickupRescheduleHistoryEntity, UUID> {
    fun findBySourceReference(sourceReference: String): PickupRescheduleHistoryEntity?
}
