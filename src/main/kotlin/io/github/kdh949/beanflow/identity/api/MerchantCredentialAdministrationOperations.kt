package io.github.kdh949.beanflow.identity.api

import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import java.time.Instant
import java.util.UUID

data class CreateMerchantCredentialCommand(
    val accountId: UUID,
    val loginId: String,
    val displayName: String,
    val passwordHash: String,
    val temporaryPasswordExpiresAt: Instant,
    val storeId: UUID,
    val membershipRole: StoreActorRole,
    val now: Instant,
)

data class ResetMerchantTemporaryPasswordCommand(
    val accountId: UUID,
    val passwordHash: String,
    val temporaryPasswordExpiresAt: Instant,
    val now: Instant,
)

data class MerchantMembershipSnapshot(
    val storeId: UUID,
    val role: StoreActorRole,
)

data class MerchantCredentialAdministrationSnapshot(
    val accountId: UUID,
    val loginId: String,
    val displayName: String,
    val accountState: MerchantAccountState,
    val lockedUntil: Instant?,
    val temporaryPasswordExpiresAt: Instant?,
    val credentialVersion: Long,
    val memberships: List<MerchantMembershipSnapshot>,
)

interface MerchantCredentialAdministrationOperations {
    fun create(command: CreateMerchantCredentialCommand): MerchantCredentialAdministrationSnapshot

    fun resetTemporaryPassword(command: ResetMerchantTemporaryPasswordCommand): MerchantCredentialAdministrationSnapshot

    fun releaseLock(
        accountId: UUID,
        now: Instant,
    ): MerchantCredentialAdministrationSnapshot

    fun findExact(loginId: String): MerchantCredentialAdministrationSnapshot?
}

interface MerchantCredentialSecurityOperations {
    fun canonicalizeLoginId(rawLoginId: String): String

    fun hashTemporaryPassword(temporaryPassword: String): String
}
