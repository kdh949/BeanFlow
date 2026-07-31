package io.github.kdh949.beanflow.fulfillment.internal

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
    RELEASED_BY_REJECTION,
}

@Entity
@Table(name = "fulfillment_pickup_reservation")
internal class PickupReservationEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "slot_id", nullable = false)
    val slotId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PickupReservationState,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "restoration_source_reference", length = 200)
    var restorationSourceReference: String? = null,
    @Version
    var version: Long = 0,
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
