package io.github.kdh949.beanflow.merchant.api

import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.ProtectedProfileChangeValue
import io.github.kdh949.beanflow.shared.api.ResolvedProfileNotificationTarget
import java.util.UUID

data class PrepareStorePublicProfileCorrection(
    val profileChangeId: UUID,
    val storeId: UUID,
    val expectedVersion: Long,
    val displayName: String?,
    val publicPhone: String?,
    val description: String?,
    val pickupInstructions: String?,
)

data class PrepareStoreOperationsContactCorrection(
    val profileChangeId: UUID,
    val storeId: UUID,
    val expectedVersion: Long,
    val operationsPhone: String?,
    val operationsEmail: String?,
)

data class PrepareStoreRepresentativeChange(
    val profileChangeId: UUID,
    val storeId: UUID,
    val expectedVersion: Long,
    val representativeName: String,
)

data class PrepareStoreSettlementAccountChange(
    val profileChangeId: UUID,
    val storeId: UUID,
    val expectedVersion: Long,
    val settlementAccountReference: String,
)

data class PrepareStoreAccessReregistration(
    val profileChangeId: UUID,
    val storeId: UUID,
    val expectedVersion: Long,
)

sealed interface PreparedStoreProfileChange {
    val profileChangeId: UUID
    val storeId: UUID
    val expectedVersion: Long

    data class PublicProfile(
        override val profileChangeId: UUID,
        override val storeId: UUID,
        override val expectedVersion: Long,
        val values: List<ProtectedProfileChangeValue>,
        val description: String?,
        val pickupInstructions: String?,
    ) : PreparedStoreProfileChange

    data class OperationsContact(
        override val profileChangeId: UUID,
        override val storeId: UUID,
        override val expectedVersion: Long,
        val values: List<ProtectedProfileChangeValue>,
    ) : PreparedStoreProfileChange

    data class Representative(
        override val profileChangeId: UUID,
        override val storeId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedStoreProfileChange

    data class SettlementAccount(
        override val profileChangeId: UUID,
        override val storeId: UUID,
        override val expectedVersion: Long,
        val value: ProtectedProfileChangeValue,
    ) : PreparedStoreProfileChange

    data class AccessReregistration(
        override val profileChangeId: UUID,
        override val storeId: UUID,
        override val expectedVersion: Long,
    ) : PreparedStoreProfileChange
}

interface StoreSupportProfileChangeOperations {
    fun currentVersion(storeId: UUID): Long

    fun preparePublicProfile(command: PrepareStorePublicProfileCorrection): PreparedStoreProfileChange.PublicProfile

    fun prepareOperationsContact(command: PrepareStoreOperationsContactCorrection): PreparedStoreProfileChange.OperationsContact

    fun prepareRepresentative(command: PrepareStoreRepresentativeChange): PreparedStoreProfileChange.Representative

    fun prepareSettlementAccount(command: PrepareStoreSettlementAccountChange): PreparedStoreProfileChange.SettlementAccount

    fun prepareAccessReregistration(command: PrepareStoreAccessReregistration): PreparedStoreProfileChange.AccessReregistration

    fun apply(prepared: PreparedStoreProfileChange): OwnerProfileChangeResult

    /** Resolves an immutable owner snapshot transiently for Notification; callers must not persist the returned bytes. */
    fun resolveNotificationTarget(targetId: UUID): ResolvedProfileNotificationTarget
}
