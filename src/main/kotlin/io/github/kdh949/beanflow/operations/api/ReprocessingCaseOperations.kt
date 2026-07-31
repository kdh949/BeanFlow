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
