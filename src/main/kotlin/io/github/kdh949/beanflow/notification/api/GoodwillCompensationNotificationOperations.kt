package io.github.kdh949.beanflow.notification.api

import java.time.Instant
import java.util.UUID

data class RequestGoodwillCompensationNotificationCommand(
    val compensationRequestId: UUID,
    val relatedOrderId: UUID?,
    val customerId: UUID,
    val storeId: UUID?,
    val benefitType: String,
    val amountKrw: Long,
    val issuedAt: Instant,
    val correlationId: String,
)

data class AcceptedGoodwillCompensationNotification(
    val deliveryId: UUID,
    val state: String,
)

data class GoodwillCompensationNotificationView(
    val deliveryId: UUID,
    val state: String,
    val updatedAt: Instant,
)

interface GoodwillCompensationNotificationOperations {
    /** Persists independently after the benefit transaction commits. */
    fun requestGoodwill(command: RequestGoodwillCompensationNotificationCommand): AcceptedGoodwillCompensationNotification

    fun findGoodwill(deliveryId: UUID): GoodwillCompensationNotificationView?
}
