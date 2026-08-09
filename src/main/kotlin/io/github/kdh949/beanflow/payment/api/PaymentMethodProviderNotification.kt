package io.github.kdh949.beanflow.payment.api

import java.time.Instant

data class VerifiedPaymentMethodProviderNotification(
    val provider: String,
    val notificationId: String,
    val notificationType: String,
    val tokenReference: String,
    val occurredAt: Instant,
)

enum class PaymentMethodProviderNotificationResult {
    MAPPED,
    MANUAL_REVIEW,
    DUPLICATE_TERMINAL,
}

fun interface PaymentMethodProviderNotificationOperations {
    fun accept(notification: VerifiedPaymentMethodProviderNotification): PaymentMethodProviderNotificationResult
}
