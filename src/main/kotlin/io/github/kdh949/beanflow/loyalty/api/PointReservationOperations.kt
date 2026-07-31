package io.github.kdh949.beanflow.loyalty.api

import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import java.time.Instant
import java.util.UUID

data class ReservePointsCommand(
    val orderId: UUID,
    val customerId: UUID,
    val amountKrw: Long,
    val reservationExpiresAt: Instant,
    val sourceReference: String,
)

data class PointAllocation(
    val pointLotId: UUID,
    val amountKrw: Long,
)

data class PointReservationResult(
    val reservationId: UUID,
    val allocations: List<PointAllocation>,
)

enum class ExpiredPointRestorationMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class RestorePointsByRejectionCommand(
    val orderId: UUID,
    val rejectedAt: Instant,
    val sourceReference: String,
    val mode: ExpiredPointRestorationMode,
    val compensationValidityDays: Int,
)

interface PointReservationOperations {
    fun reserve(command: ReservePointsCommand): PointReservationResult

    fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport

    fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun restoreUsedByRejection(command: RestorePointsByRejectionCommand): ReservationTransitionReport
}
