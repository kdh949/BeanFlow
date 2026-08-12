package io.github.kdh949.beanflow.shared.api

import java.util.UUID

enum class ProfileNotificationTargetKind {
    OLD,
    NEW,
    CURRENT,
}

enum class ProfileNotificationChannel {
    PHONE,
    EMAIL,
}

data class ProtectedProfileChangeValue(
    val field: PersonalDataField,
    val encrypted: EncryptedPersonalData,
    val masked: String,
    val exactIndexes: List<BlindIndex> = emptyList(),
) {
    override fun toString(): String =
        "ProtectedProfileChangeValue(field=$field, encrypted=<redacted>, masked=<redacted>, indexes=<redacted>)"
}

data class OwnerProfileNotificationTarget(
    val targetId: UUID,
    val kind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    val maskedDestination: String,
) {
    override fun toString(): String =
        "OwnerProfileNotificationTarget(targetId=$targetId, kind=$kind, channel=$channel, destination=<redacted>)"
}

data class OwnerProfileChangeResult(
    val ownerChangeId: UUID,
    val previousVersion: Long,
    val currentVersion: Long,
    val maskedBefore: String,
    val maskedAfter: String,
    val notificationTargets: List<OwnerProfileNotificationTarget>,
) {
    override fun toString(): String =
        "OwnerProfileChangeResult(ownerChangeId=$ownerChangeId, versions=$previousVersion->$currentVersion, values=<redacted>, " +
            "notificationTargets=${notificationTargets.size})"
}

class ResolvedProfileNotificationTarget(
    val targetId: UUID,
    val kind: ProfileNotificationTargetKind,
    val channel: ProfileNotificationChannel,
    destination: ByteArray,
) {
    private val rawDestination = destination.copyOf()

    fun destinationBytes(): ByteArray = rawDestination.copyOf()

    override fun toString(): String =
        "ResolvedProfileNotificationTarget(targetId=$targetId, kind=$kind, channel=$channel, destination=<redacted>)"
}
