package io.github.kdh949.beanflow.notification.api

import java.util.UUID

enum class BreakGlassSecurityNotificationEvent {
    REQUESTED,
    APPROVED,
    REVEALED,
}

data class SendBreakGlassSecurityNotificationCommand(
    val notificationIntentId: UUID,
    val breakGlassRequestId: UUID,
    val event: BreakGlassSecurityNotificationEvent,
)

enum class BreakGlassSecurityNotificationResult {
    SENT,
    RETRYABLE_FAILURE,
    UNKNOWN,
    PERMANENT_FAILURE,
}

interface BreakGlassSecurityNotificationOperations {
    /** Called outside the notification-intent database transaction. */
    fun send(command: SendBreakGlassSecurityNotificationCommand): BreakGlassSecurityNotificationResult
}
