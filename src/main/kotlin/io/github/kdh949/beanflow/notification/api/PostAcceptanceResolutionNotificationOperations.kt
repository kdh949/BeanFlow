package io.github.kdh949.beanflow.notification.api

import java.time.Instant
import java.util.UUID

data class RequestPostAcceptanceResolutionNotificationCommand(
    val resolutionId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val outcome: String,
    val resolutionState: String,
    val occurredAt: Instant,
    val correlationId: String,
)

data class AcceptedPostAcceptanceResolutionNotification(
    val deliveryId: UUID,
    val state: String,
)

data class PostAcceptanceResolutionNotificationView(
    val deliveryId: UUID,
    val state: String,
    val updatedAt: Instant,
)

interface PostAcceptanceResolutionNotificationOperations {
    fun request(
        command: RequestPostAcceptanceResolutionNotificationCommand,
    ): AcceptedPostAcceptanceResolutionNotification

    fun find(deliveryId: UUID): PostAcceptanceResolutionNotificationView?
}
