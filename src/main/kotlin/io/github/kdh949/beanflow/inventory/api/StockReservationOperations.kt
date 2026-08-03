package io.github.kdh949.beanflow.inventory.api

import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import java.time.Instant
import java.util.UUID

data class StockRequirement(
    val sellableUnitId: UUID,
    val quantity: Long,
)

data class ReserveStockCommand(
    val orderId: UUID,
    val storeId: UUID,
    val requirements: List<StockRequirement>,
    val expiresAt: Instant,
    val sourceReference: String,
)

data class RestoreStockAfterTerminationCommand(
    val orderId: UUID,
    val terminatedAt: Instant,
    val sourceReference: String,
    val trigger: OrderTerminationTrigger,
)

interface StockReservationOperations {
    fun reserve(command: ReserveStockCommand): List<UUID>

    fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport

    fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun expire(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun restoreConfirmedAfterTermination(command: RestoreStockAfterTerminationCommand): ReservationTransitionReport
}
