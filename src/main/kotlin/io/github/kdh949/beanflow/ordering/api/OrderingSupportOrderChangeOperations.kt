package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

enum class SupportOrderChangeOwnerResult {
    APPLIED,
    ALREADY_APPLIED,
    RESOLUTION_REQUIRED,
}

data class SupportPickupRescheduleCommand(
    val supportRequestId: UUID,
    val supportExecutionId: UUID,
    val actorId: UUID,
    val orderId: UUID,
    val expectedOrderVersion: Long,
    val newPickupSlotId: UUID,
    val acceptedStoreAuthorizationId: UUID?,
    val sourceReference: String,
)

data class SupportOrderCancellationCommand(
    val supportRequestId: UUID,
    val supportExecutionId: UUID,
    val actorId: UUID,
    val orderId: UUID,
    val expectedOrderVersion: Long,
    val reasonCode: CustomerCancellationReasonCode,
    val reasonDetail: String?,
    val acceptedStoreAuthorizationId: UUID?,
    val sourceReference: String,
)

data class SupportOrderChangeOwnerReport(
    val result: SupportOrderChangeOwnerResult,
    val orderId: UUID,
    val previousState: String,
    val currentState: String,
    val previousPickupSlotId: UUID,
    val currentPickupSlotId: UUID,
    val orderVersion: Long,
    val paymentRecoveryState: String? = null,
)

interface OrderingSupportPickupRescheduleOperations {
    fun reschedule(command: SupportPickupRescheduleCommand): SupportOrderChangeOwnerReport
}

interface OrderingSupportOrderCancellationOperations {
    fun cancel(command: SupportOrderCancellationCommand): SupportOrderChangeOwnerReport
}
