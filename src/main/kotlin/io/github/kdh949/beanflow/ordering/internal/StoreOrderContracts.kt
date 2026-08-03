package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrderCompensationState
import jakarta.validation.constraints.Size
import java.time.Instant
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
    val order: OrderResponse,
    val compensationRecovery: StoreCompensationSummary?,
)

internal data class StoreCompensationSummary(
    val trigger: io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger,
    val state: OrderCompensationState,
    val updatedAt: Instant,
)
