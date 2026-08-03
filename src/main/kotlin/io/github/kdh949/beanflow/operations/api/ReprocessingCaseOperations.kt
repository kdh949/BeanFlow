package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

data class OpenReprocessingCaseCommand(
    val ownerReference: String,
    val reason: String,
    val correlationId: String,
    val now: Instant,
)

interface ReprocessingCaseOperations {
    fun openPaymentCase(command: OpenReprocessingCaseCommand): UUID
}

interface NotificationReprocessingCaseOperations {
    fun openNotificationCase(command: OpenReprocessingCaseCommand): UUID
}

interface EventPublicationReprocessingCaseOperations {
    fun openEventPublicationCase(command: OpenReprocessingCaseCommand): UUID
}

interface AcceptanceTimeoutWorkReprocessingCaseOperations {
    fun openAcceptanceTimeoutWorkCase(command: OpenReprocessingCaseCommand): UUID
}

interface SettlementLateItemReprocessingCaseOperations {
    fun openLateItemCase(command: OpenReprocessingCaseCommand): UUID
}

interface SettlementAdjustmentReprocessingCaseOperations {
    fun openAdjustmentCase(command: OpenReprocessingCaseCommand): UUID
}

interface SettlementDisputeReprocessingCaseOperations {
    fun openDisputeCase(command: OpenReprocessingCaseCommand): UUID

    fun resolveDisputeCase(
        ownerReference: String,
        resolution: String,
        now: Instant,
    )
}
