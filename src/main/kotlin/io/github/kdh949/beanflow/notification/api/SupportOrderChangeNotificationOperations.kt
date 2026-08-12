package io.github.kdh949.beanflow.notification.api

import java.time.Instant
import java.util.UUID

data class RequestSupportPickupRescheduledNotificationCommand(
    val executionId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val orderAggregateVersion: Long,
    val previousPickupSlotId: UUID,
    val currentPickupSlotId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class AcceptedSupportOrderChangeNotification(
    val deliveryId: UUID,
    val state: String,
)

interface SupportOrderChangeNotificationOperations {
    fun requestPickupRescheduled(command: RequestSupportPickupRescheduledNotificationCommand): AcceptedSupportOrderChangeNotification
}
