package io.github.kdh949.beanflow.operations.api

import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import java.time.Instant
import java.util.UUID

enum class MerchantCredentialMembershipRole {
    OWNER,
    STAFF,
}

data class ProvisionMerchantCredentialCommand(
    val accountId: UUID,
    val loginId: String,
    val displayName: String,
    val passwordHash: String,
    val temporaryPasswordExpiresAt: Instant,
    val storeId: UUID,
    val membershipRole: MerchantCredentialMembershipRole,
    val now: Instant,
)

data class ReplaceMerchantTemporaryPasswordCommand(
    val accountId: UUID,
    val passwordHash: String,
    val temporaryPasswordExpiresAt: Instant,
    val now: Instant,
)

data class ProvisionedMerchantMembership(
    val storeId: UUID,
    val role: MerchantCredentialMembershipRole,
)

data class ProvisionedMerchantCredential(
    val accountId: UUID,
    val loginId: String,
    val displayName: String,
    val accountState: MerchantAccountState,
    val lockedUntil: Instant?,
    val temporaryPasswordExpiresAt: Instant?,
    val credentialVersion: Long,
    val memberships: List<ProvisionedMerchantMembership>,
)

/** Operations-owned password policy port implemented by Identity outside a database transaction. */
interface MerchantCredentialSecurityPort {
    fun canonicalizeLoginId(rawLoginId: String): String

    fun hashTemporaryPassword(temporaryPassword: String): String
}

/** Operations-owned outbound port implemented by Identity in the caller's transaction. */
interface MerchantCredentialProvisioningPort {
    fun create(command: ProvisionMerchantCredentialCommand): ProvisionedMerchantCredential

    fun resetTemporaryPassword(command: ReplaceMerchantTemporaryPasswordCommand): ProvisionedMerchantCredential

    fun releaseLock(
        accountId: UUID,
        now: Instant,
    ): ProvisionedMerchantCredential

    fun findExact(loginId: String): ProvisionedMerchantCredential?
}
