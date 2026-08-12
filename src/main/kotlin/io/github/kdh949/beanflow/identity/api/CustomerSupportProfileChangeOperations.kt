package io.github.kdh949.beanflow.identity.api

import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.ProtectedProfileChangeValue
import io.github.kdh949.beanflow.shared.api.ResolvedProfileNotificationTarget
import java.util.UUID

data class PrepareCustomerDisplayNameCorrection(
    val profileChangeId: UUID,
    val customerId: UUID,
    val expectedVersion: Long,
    val displayName: String,
)

data class PrepareCustomerLegalNameCorrection(
    val profileChangeId: UUID,
    val customerId: UUID,
    val expectedVersion: Long,
    val legalName: String,
)

data class PrepareCustomerPrimaryPhoneChange(
    val profileChangeId: UUID,
    val customerId: UUID,
    val expectedVersion: Long,
    val primaryPhone: String,
)

data class PrepareCustomerCredentialReset(
    val profileChangeId: UUID,
    val customerId: UUID,
    val expectedVersion: Long,
)

sealed interface PreparedCustomerProfileChange {
    val profileChangeId: UUID
    val customerId: UUID
    val expectedVersion: Long

    data class DisplayName(
        override val profileChangeId: UUID,
        override val customerId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCustomerProfileChange

    data class LegalName(
        override val profileChangeId: UUID,
        override val customerId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCustomerProfileChange

    data class PrimaryPhone(
        override val profileChangeId: UUID,
        override val customerId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedCustomerProfileChange

    data class CredentialReset(
        override val profileChangeId: UUID,
        override val customerId: UUID,
        override val expectedVersion: Long,
    ) : PreparedCustomerProfileChange
}

interface CustomerSupportProfileChangeOperations {
    fun currentVersion(customerId: UUID): Long

    fun prepareDisplayName(command: PrepareCustomerDisplayNameCorrection): PreparedCustomerProfileChange.DisplayName

    fun prepareLegalName(command: PrepareCustomerLegalNameCorrection): PreparedCustomerProfileChange.LegalName

    fun preparePrimaryPhone(command: PrepareCustomerPrimaryPhoneChange): PreparedCustomerProfileChange.PrimaryPhone

    fun prepareCredentialReset(command: PrepareCustomerCredentialReset): PreparedCustomerProfileChange.CredentialReset

    fun apply(prepared: PreparedCustomerProfileChange): OwnerProfileChangeResult

    /** Resolves an immutable owner snapshot transiently for Notification; callers must not persist the returned bytes. */
    fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget
}
