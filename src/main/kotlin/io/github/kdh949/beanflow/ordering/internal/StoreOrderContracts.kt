package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrderCompensationState
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal enum class StoreOrderTargetState {
    ACCEPTED,
    PREPARING,
    READY,
    COMPLETED,
    REJECTED,
}

internal data class StoreOrderTransitionRequest(
    val targetState: StoreOrderTargetState,
    @field:Size(max = 500)
    val reason: String?,
)

internal data class StoreOrderResult(
    val order: StoreOrderResponse,
    val compensationRecovery: StoreCompensationSummary?,
)

internal data class StoreOrderResponse(
    val orderId: UUID,
    val storeId: UUID,
    val publicReference: String,
    val pickupNumber: String,
    val pickupBusinessDate: LocalDate,
    val storeName: String,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val state: String,
    val reservationExpiresAt: Instant?,
    val paidAt: Instant?,
    val acceptanceWarningAt: Instant?,
    val acceptanceWarningRequestedAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
    val acceptedAt: Instant?,
    val rejectedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationCause: io.github.kdh949.beanflow.ordering.api.OrderCancellationCause?,
    val rejectionReason: String?,
    val lines: List<OrderLineResponse>,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class StoreCompensationSummary(
    val trigger: io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger,
    val state: OrderCompensationState,
    val updatedAt: Instant,
)
