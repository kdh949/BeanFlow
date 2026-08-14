package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class MerchantAccountTest {
    @Test
    fun `temporary password expires exactly at its deadline without activating account`() {
        val createdAt = Instant.parse("2026-08-13T00:00:00Z")
        val expiresAt = createdAt.plus(24, ChronoUnit.HOURS)
        val account = initialAccount(createdAt, expiresAt)

        assertThat(account.temporaryPasswordUsable(expiresAt.minusNanos(1))).isTrue()
        assertThat(account.temporaryPasswordUsable(expiresAt)).isFalse()
        account.materializeTemporaryPasswordExpiry(expiresAt)

        assertThat(account.state).isEqualTo(MerchantAccountState.EXPIRED)
        assertThat(account.temporaryPasswordExpiresAt).isEqualTo(expiresAt)
        assertThat(account.passwordChangedAt).isNull()
    }

    @Test
    fun `lock is an overlay and expiry preserves initial password lifecycle`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val account = initialAccount(now, now.plus(24, ChronoUnit.HOURS))

        account.lock(now.plus(15, ChronoUnit.MINUTES), now)
        assertThat(account.state).isEqualTo(MerchantAccountState.INITIAL_PASSWORD)
        assertThat(account.credentialVersion).isOne()
        assertThat(account.loginAllowed(now.plus(15, ChronoUnit.MINUTES).minusNanos(1))).isFalse()

        account.clearExpiredLock(now.plus(15, ChronoUnit.MINUTES))
        assertThat(account.state).isEqualTo(MerchantAccountState.INITIAL_PASSWORD)
        assertThat(account.lockedUntil).isNull()
        assertThat(account.credentialVersion).isOne()
    }

    @Test
    fun `password change activates account and invalidates all previous credentials`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val account = initialAccount(now, now.plus(24, ChronoUnit.HOURS))

        account.changePassword("new-hash", now.plusSeconds(60))

        assertThat(account.passwordHash).isEqualTo("new-hash")
        assertThat(account.state).isEqualTo(MerchantAccountState.ACTIVE)
        assertThat(account.temporaryPasswordExpiresAt).isNull()
        assertThat(account.passwordChangedAt).isEqualTo(now.plusSeconds(60))
        assertThat(account.credentialVersion).isOne()
    }

    @Test
    fun `expired temporary password cannot be changed`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val account = initialAccount(now, now.plusSeconds(60))

        assertThatThrownBy { account.changePassword("new-hash", now.plusSeconds(60)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun initialAccount(
        createdAt: Instant,
        expiresAt: Instant,
    ) = MerchantAccountEntity(
        id = UUID.randomUUID(),
        loginId = "merchant.user",
        passwordHash = "old-hash",
        displayName = "Merchant",
        state = MerchantAccountState.INITIAL_PASSWORD,
        temporaryPasswordExpiresAt = expiresAt,
        passwordChangedAt = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
