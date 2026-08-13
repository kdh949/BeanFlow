package io.github.kdh949.beanflow.identity.internal

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

internal enum class CustomerAccountState {
    ACTIVE,
    LOCKED,
}

@Entity
@Table(name = "identity_customer_account")
internal class CustomerAccountEntity(
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
    var state: CustomerAccountState = CustomerAccountState.ACTIVE,
    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
) {
    fun lock(
        until: Instant,
        now: Instant,
    ) {
        require(until.isAfter(now)) { "Customer lock deadline must be in the future" }
        state = CustomerAccountState.LOCKED
        lockedUntil = until
        credentialVersion += 1
        updatedAt = now
    }

    fun activateAfterExpiredLock(now: Instant) {
        check(state == CustomerAccountState.LOCKED && lockedUntil?.let { !now.isBefore(it) } == true) {
            "Only an expired customer lock can be activated"
        }
        state = CustomerAccountState.ACTIVE
        lockedUntil = null
        updatedAt = now
    }

    fun snapshot(): CustomerCredentialSnapshot =
        CustomerCredentialSnapshot(id, passwordHash, credentialVersion, displayName, state, lockedUntil)
}

internal data class CustomerCredentialSnapshot(
    val id: UUID,
    val passwordHash: String,
    val credentialVersion: Long,
    val displayName: String,
    val state: CustomerAccountState,
    val lockedUntil: Instant?,
)

internal interface CustomerAccountJpaRepository : JpaRepository<CustomerAccountEntity, UUID> {
    fun findByLoginId(loginId: String): CustomerAccountEntity?

    fun existsByLoginId(loginId: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from CustomerAccountEntity account where account.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): CustomerAccountEntity?
}
