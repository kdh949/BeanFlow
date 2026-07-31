package io.github.kdh949.beanflow.inventory.internal

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
@Table(name = "inventory_sellable_stock")
internal class SellableStockEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "available_quantity", nullable = false)
    var availableQuantity: Long,
    @Column(name = "reserved_quantity", nullable = false)
    var reservedQuantity: Long = 0,
    @Column(name = "confirmed_quantity", nullable = false)
    var confirmedQuantity: Long = 0,
    @Version
    var version: Long = 0,
) {
    fun reserve(quantity: Long) {
        check(quantity > 0)
        if (availableQuantity < quantity) {
            throw IllegalStateException("Insufficient stock")
        }
        availableQuantity -= quantity
        reservedQuantity += quantity
    }

    fun confirm(quantity: Long) {
        check(quantity > 0 && reservedQuantity >= quantity)
        reservedQuantity -= quantity
        confirmedQuantity += quantity
    }

    fun release(quantity: Long) {
        check(quantity > 0 && reservedQuantity >= quantity)
        reservedQuantity -= quantity
        availableQuantity += quantity
    }

    fun restoreConfirmed(quantity: Long) {
        check(quantity > 0 && confirmedQuantity >= quantity)
        confirmedQuantity -= quantity
        availableQuantity += quantity
    }
}

internal enum class StockReservationState {
    RESERVED,
    CONFIRMED,
    EXPIRED,
    RELEASED,
    RELEASED_BY_REJECTION,
}

@Entity
@Table(name = "inventory_stock_reservation")
internal class StockReservationEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "sellable_unit_id", nullable = false)
    val sellableUnitId: UUID,
    @Column(nullable = false)
    val quantity: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: StockReservationState,
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

internal interface SellableStockJpaRepository : JpaRepository<SellableStockEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select stock from SellableStockEntity stock where stock.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SellableStockEntity?
}

internal interface StockReservationJpaRepository : JpaRepository<StockReservationEntity, UUID> {
    fun findByOrderIdOrderBySellableUnitId(orderId: UUID): List<StockReservationEntity>

    fun findBySourceReferenceAndSellableUnitId(
        sourceReference: String,
        sellableUnitId: UUID,
    ): StockReservationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select reservation from StockReservationEntity reservation " +
            "where reservation.orderId = :orderId order by reservation.sellableUnitId",
    )
    fun findLockedByOrderId(
        @Param("orderId") orderId: UUID,
    ): List<StockReservationEntity>
}
