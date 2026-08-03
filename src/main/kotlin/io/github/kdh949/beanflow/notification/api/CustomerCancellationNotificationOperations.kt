package io.github.kdh949.beanflow.notification.api

import java.time.Instant
import java.util.UUID

data class RequestCustomerCancellationAcceptedNotificationCommand(
    val eventId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val orderAggregateVersion: Long,
    val cancelledAt: Instant,
    val correlationId: String,
)

data class AcceptedCustomerCancellationNotification(
    val deliveryId: UUID,
    val state: String,
)

interface CustomerCancellationNotificationOperations {
    fun requestAccepted(command: RequestCustomerCancellationAcceptedNotificationCommand): AcceptedCustomerCancellationNotification
}
