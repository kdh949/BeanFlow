package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class VerificationChallengeTest {
    private val issuedAt = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun `challenge has an exact five minute validity boundary`() {
        val challenge = issuedChallenge()

        assertThat(challenge.claimVerification(issuedAt.plusSeconds(5 * 60 - 1))).isEqualTo(ChallengeState.VERIFYING)
    }

    @Test
    fun `challenge rejects verification at the expiry instant`() {
        val challenge = issuedChallenge()

        assertThatThrownBy { challenge.claimVerification(issuedAt.plusSeconds(5 * 60)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(challenge.state).isEqualTo(ChallengeState.EXPIRED)
    }

    @Test
    fun `provider issue completion at the expiry instant is retained as expired`() {
        val challenge =
            VerificationChallenge.request(
                id = UUID.fromString("42000000-0000-0000-0000-000000000003"),
                sessionId = UUID.fromString("42000000-0000-0000-0000-000000000002"),
                channel = VerificationChannel.REGISTERED_EMAIL,
                requestedAt = issuedAt,
            )

        assertThat(challenge.completeIssue("opaque-provider-reference", issuedAt.plusSeconds(5 * 60)))
            .isEqualTo(ChallengeState.EXPIRED)
    }

    @Test
    fun `terminal provider outcome cannot be replayed`() {
        val challenge = issuedChallenge()
        challenge.claimVerification(issuedAt.plusSeconds(1))
        challenge.complete(ChallengeOutcome.INVALID, issuedAt.plusSeconds(2))

        assertThatThrownBy { challenge.claimVerification(issuedAt.plusSeconds(3)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(challenge.state).isEqualTo(ChallengeState.INVALID)
    }

    @Test
    fun `unknown provider result remains explicit and terminal`() {
        val challenge = issuedChallenge()
        challenge.claimVerification(issuedAt.plusSeconds(1))

        assertThat(challenge.complete(ChallengeOutcome.UNKNOWN, issuedAt.plusSeconds(2)))
            .isEqualTo(ChallengeState.VERIFICATION_UNKNOWN)
        assertThatThrownBy { challenge.claimVerification(issuedAt.plusSeconds(3)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun issuedChallenge(): VerificationChallenge =
        VerificationChallenge.request(
            id = UUID.fromString("42000000-0000-0000-0000-000000000001"),
            sessionId = UUID.fromString("42000000-0000-0000-0000-000000000002"),
            channel = VerificationChannel.REGISTERED_PHONE,
            requestedAt = issuedAt,
        ).also {
            it.completeIssue("opaque-provider-reference", issuedAt)
        }
}
