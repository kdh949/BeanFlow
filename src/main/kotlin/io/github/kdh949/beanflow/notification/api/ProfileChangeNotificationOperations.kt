package io.github.kdh949.beanflow.notification.api

import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import java.time.Instant
import java.util.UUID

enum class ProfileNotificationOwnerType {
    CUSTOMER,
    STORE,
    EXTERNAL_COURIER,
}

data class RequestProfileChangeNotificationCommand(
    val profileChangeId: UUID,
    val ownerType: ProfileNotificationOwnerType,
    val ownerTargetId: UUID,
    val targetKind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val purpose: String,
    val occurredAt: Instant,
    val correlationId: String,
)

data class AcceptedProfileChangeNotification(
    val deliveryId: UUID,
    val state: String,
)

interface ProfileChangeNotificationOperations {
    /** Persists independently after the owner change and Audit transaction commits. */
    fun requestProfileChange(command: RequestProfileChangeNotificationCommand): AcceptedProfileChangeNotification
}
