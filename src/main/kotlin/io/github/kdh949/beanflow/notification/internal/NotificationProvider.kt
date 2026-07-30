package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.notification.internal.domain.NotificationRecipientType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTemplate
import java.util.UUID

internal data class NotificationProviderRequest(
    val deliveryId: UUID,
    val recipientType: NotificationRecipientType,
    val recipientId: UUID,
    val logicalChannel: NotificationLogicalChannel,
    val template: NotificationTemplate,
    val payloadJson: String,
    val providerIdempotencyKey: String,
)

internal sealed interface NotificationProviderResult {
    data class Acknowledged(
        val providerDeliveryReference: String,
    ) : NotificationProviderResult

    data class Failed(
        val code: String,
    ) : NotificationProviderResult

    data class Unknown(
        val code: String,
    ) : NotificationProviderResult
}

internal class NotificationTransportFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun interface NotificationProvider {
    fun send(request: NotificationProviderRequest): NotificationProviderResult
}
