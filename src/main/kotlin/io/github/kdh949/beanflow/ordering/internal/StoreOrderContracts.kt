package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.RejectionCompensationState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
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
    val rejectionRecovery: RejectionRecoveryResponse?,
)

internal data class StoreOrderTransitionResult(
    val order: OrderResponse,
    val rejectionRecovery: RejectionRecoveryResponse?,
    val replayed: Boolean,
)

internal data class RejectionRecoveryResponse(
    val caseId: UUID,
    val policyVersion: Long,
    val state: RejectionCompensationState,
    val steps: List<RejectionRecoveryStepResponse>,
    val updatedAt: Instant,
)

internal data class RejectionRecoveryStepResponse(
    val type: RejectionCompensationStepType,
    val state: RejectionCompensationStepState,
    val attemptCount: Int,
    val lastErrorCode: String?,
)
