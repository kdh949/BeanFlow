package io.github.kdh949.beanflow.delivery.api

import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.ProtectedProfileChangeValue
import io.github.kdh949.beanflow.shared.api.ResolvedProfileNotificationTarget
import java.util.UUID

data class PrepareCourierDisplayNameCorrection(
    val profileChangeId: UUID,
    val externalCourierId: UUID,
    val expectedVersion: Long,
    val displayName: String,
)

data class PrepareCourierRelayContactCorrection(
    val profileChangeId: UUID,
    val externalCourierId: UUID,
    val expectedVersion: Long,
    val relayPhone: String?,
    val relayEmail: String?,
)

data class PrepareCourierProviderIdentityChange(
    val profileChangeId: UUID,
    val externalCourierId: UUID,
    val expectedVersion: Long,
    val providerCourierReference: String,
)

data class PrepareCourierPayoutReferenceChange(
    val profileChangeId: UUID,
    val externalCourierId: UUID,
    val expectedVersion: Long,
    val payoutReference: String,
)

data class PrepareCourierProviderReregistration(
    val profileChangeId: UUID,
    val externalCourierId: UUID,
    val expectedVersion: Long,
)

sealed interface PreparedCourierProfileChange {
    val profileChangeId: UUID
    val externalCourierId: UUID
    val expectedVersion: Long

    data class DisplayName(
        override val profileChangeId: UUID,
        override val externalCourierId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCourierProfileChange

    data class RelayContact(
        override val profileChangeId: UUID,
        override val externalCourierId: UUID,
        override val expectedVersion: Long,
        val values: List<ProtectedProfileChangeValue>,
    ) : PreparedCourierProfileChange

    data class ProviderIdentity(
        override val profileChangeId: UUID,
        override val externalCourierId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCourierProfileChange

    data class PayoutReference(
        override val profileChangeId: UUID,
        override val externalCourierId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCourierProfileChange

    data class ProviderReregistration(
        override val profileChangeId: UUID,
        override val externalCourierId: UUID,
        override val expectedVersion: Long,
    ) : PreparedCourierProfileChange
}

interface ExternalCourierSupportProfileChangeOperations {
    fun currentVersion(externalCourierId: UUID): Long

    fun prepareDisplayName(command: PrepareCourierDisplayNameCorrection): PreparedCourierProfileChange.DisplayName

    fun prepareRelayContact(command: PrepareCourierRelayContactCorrection): PreparedCourierProfileChange.RelayContact

    fun prepareProviderIdentity(command: PrepareCourierProviderIdentityChange): PreparedCourierProfileChange.ProviderIdentity

    fun preparePayoutReference(command: PrepareCourierPayoutReferenceChange): PreparedCourierProfileChange.PayoutReference

    fun prepareProviderReregistration(
        command: PrepareCourierProviderReregistration,
    ): PreparedCourierProfileChange.ProviderReregistration

    fun apply(prepared: PreparedCourierProfileChange): OwnerProfileChangeResult

    /** Resolves an immutable owner snapshot transiently for Notification; callers must not persist the returned bytes. */
    fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget
}
