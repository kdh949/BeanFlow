package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "identity_merchant_account")
internal class MerchantAccountEntity(
    @Id
    val id: UUID,
    @Column(name = "login_id", nullable = false, unique = true, length = 32)
    val loginId: String,
    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,
    @Column(name = "credential_version", nullable = false)
    var credentialVersion: Long = 0,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var state: MerchantAccountState,
    @Column(name = "temporary_password_expires_at")
    var temporaryPasswordExpiresAt: Instant?,
    @Column(name = "password_changed_at")
    var passwordChangedAt: Instant?,
    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
) {
    fun temporaryPasswordUsable(now: Instant): Boolean =
        state == MerchantAccountState.INITIAL_PASSWORD && temporaryPasswordExpiresAt?.let(now::isBefore) == true

    fun loginAllowed(now: Instant): Boolean =
        state != MerchantAccountState.EXPIRED &&
            (state != MerchantAccountState.INITIAL_PASSWORD || temporaryPasswordUsable(now)) &&
            lockedUntil?.let(now::isBefore) != true

    fun materializeTemporaryPasswordExpiry(now: Instant) {
        check(state == MerchantAccountState.INITIAL_PASSWORD && !temporaryPasswordUsable(now)) {
            "Only an expired initial password can be materialized"
        }
        state = MerchantAccountState.EXPIRED
        updatedAt = now
    }

    fun lock(
        until: Instant,
        now: Instant,
    ) {
        require(until.isAfter(now)) { "Merchant lock deadline must be in the future" }
        lockedUntil = until
        credentialVersion += 1
        updatedAt = now
    }

    fun clearExpiredLock(now: Instant) {
        check(lockedUntil?.let { !now.isBefore(it) } == true) { "Only an expired merchant lock can be cleared" }
        lockedUntil = null
        updatedAt = now
    }

    fun changePassword(
        newPasswordHash: String,
        now: Instant,
    ) {
        check(loginAllowed(now)) { "Merchant credential is not available for password change" }
        passwordHash = newPasswordHash
        state = MerchantAccountState.ACTIVE
        temporaryPasswordExpiresAt = null
        passwordChangedAt = now
        credentialVersion += 1
        updatedAt = now
    }

    fun resetTemporaryPassword(
        newPasswordHash: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        require(expiresAt.isAfter(now)) { "Temporary password deadline must be in the future" }
        passwordHash = newPasswordHash
        state = MerchantAccountState.INITIAL_PASSWORD
        temporaryPasswordExpiresAt = expiresAt
        passwordChangedAt = null
        lockedUntil = null
        credentialVersion += 1
        updatedAt = now
    }

    fun releaseLock(now: Instant) {
        lockedUntil = null
        updatedAt = now
    }

    fun snapshot(): MerchantCredentialSnapshot =
        MerchantCredentialSnapshot(
            id,
            loginId,
            passwordHash,
            credentialVersion,
            displayName,
            state,
            temporaryPasswordExpiresAt,
            lockedUntil,
        )
}

internal data class MerchantCredentialSnapshot(
    val id: UUID,
    val loginId: String,
    val passwordHash: String,
    val credentialVersion: Long,
    val displayName: String,
    val state: MerchantAccountState,
    val temporaryPasswordExpiresAt: Instant?,
    val lockedUntil: Instant?,
)

internal interface MerchantAccountJpaRepository : JpaRepository<MerchantAccountEntity, UUID> {
    fun findByLoginId(loginId: String): MerchantAccountEntity?

    fun existsByLoginId(loginId: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from MerchantAccountEntity account where account.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): MerchantAccountEntity?
}
