package io.github.kdh949.beanflow.loyalty.api

import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
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

enum class PointIssuerType {
    PLATFORM,
    BRAND,
    STORE,
}

data class PointReservationAllocation(
    val pointLotId: UUID,
    val issuerType: PointIssuerType,
    val issuerReference: String,
    val finalAllocationKrw: Long,
) {
    init {
        require(issuerReference.isNotBlank()) { "Point issuer reference must not be blank" }
        require(finalAllocationKrw > 0) { "Point allocation must be positive" }
    }
}

data class PointReservationResult(
    val reservationId: UUID,
    val allocations: List<PointReservationAllocation>,
)

enum class ExpiredPointRestorationMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class RestorePointsAfterTerminationCommand(
    val orderId: UUID,
    val terminatedAt: Instant,
    val sourceReference: String,
    val trigger: OrderTerminationTrigger,
    val policyVersionId: Long,
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

    fun restoreUsedAfterTermination(command: RestorePointsAfterTerminationCommand): ReservationTransitionReport
}
