package io.github.kdh949.beanflow.payment

import io.github.kdh949.beanflow.payment.internal.domain.Refund
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class RefundStateTest {
    @Test
    fun `first claim requests refund and unknown retry only looks up provider`() {
        val refund = refund()
        val firstToken = UUID.randomUUID()

        assertThat(refund.claim(firstToken, NOW, LEASE, MAX_ATTEMPTS)).isEqualTo(RefundClaimMode.REQUEST)
        refund.recordUnknown("timeout", NOW, RETRY_DELAYS, MAX_ATTEMPTS)

        assertThat(refund.state).isEqualTo(RefundState.UNKNOWN)
        assertThat(refund.nextAttemptAt).isEqualTo(NOW.plusSeconds(10))
        assertThat(refund.claim(UUID.randomUUID(), NOW.plusSeconds(10), LEASE, MAX_ATTEMPTS))
            .isEqualTo(RefundClaimMode.LOOKUP)
        assertThat(refund.state).isEqualTo(RefundState.RECONCILING)
    }

    @Test
    fun `expired processing lease is reclaimed as lookup rather than another refund request`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE, MAX_ATTEMPTS)

        assertThatThrownBy {
            refund.claim(UUID.randomUUID(), NOW.plusSeconds(59), LEASE, MAX_ATTEMPTS)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(refund.claim(UUID.randomUUID(), NOW.plusSeconds(60), LEASE, MAX_ATTEMPTS))
            .isEqualTo(RefundClaimMode.LOOKUP)
    }

    @Test
    fun `five unknown provider results end in manual review`() {
        val refund = refund()
        var now = NOW

        repeat(MAX_ATTEMPTS) {
            refund.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)
            refund.recordUnknown("ack_lost", now, RETRY_DELAYS, MAX_ATTEMPTS)
            now = refund.nextAttemptAt ?: now
        }

        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.attemptCount).isEqualTo(5)
        assertThat(refund.nextAttemptAt).isNull()
    }

    @Test
    fun `successful full refund is terminal and records exact amount`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE, MAX_ATTEMPTS)

        refund.succeed("provider-refund", NOW)

        assertThat(refund.state).isEqualTo(RefundState.SUCCEEDED)
        assertThat(refund.succeededAmountKrw).isEqualTo(7_000)
        assertThat(refund.providerRefundReference).isEqualTo("provider-refund")
        assertThat(refund.attemptCount).isEqualTo(1)
        assertThatThrownBy {
            refund.claim(UUID.randomUUID(), NOW.plus(LEASE), LEASE, MAX_ATTEMPTS)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `explicit provider failure is terminal failed rather than unknown`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE, MAX_ATTEMPTS)

        refund.fail("refund_declined", NOW)

        assertThat(refund.state).isEqualTo(RefundState.FAILED)
        assertThat(refund.lastFailureCode).isEqualTo("REFUND_DECLINED")
        assertThat(refund.nextAttemptAt).isNull()
    }

    @Test
    fun `expired final claim becomes manual review instead of remaining processing`() {
        val refund = refund()
        var now = NOW
        repeat(MAX_ATTEMPTS - 1) {
            refund.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)
            refund.recordUnknown("ack_lost", now, RETRY_DELAYS, MAX_ATTEMPTS)
            now = requireNotNull(refund.nextAttemptAt)
        }
        refund.claim(UUID.randomUUID(), now, LEASE, MAX_ATTEMPTS)

        refund.markManualReviewAfterExpiredClaim(now.plus(LEASE), MAX_ATTEMPTS)

        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.lastFailureCode).isEqualTo("CLAIM_LEASE_EXPIRED")
        assertThat(refund.claimToken).isNull()
        assertThat(refund.claimUntil).isNull()
    }

    private fun refund(): Refund =
        Refund.request(
            id = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            requestedAmountKrw = 7_000,
            reason = "STORE_ORDER_REJECTED",
            providerIdempotencyKey = "refund-key",
            sourceReference = "event:refund",
            now = NOW,
        )

    private companion object {
        const val MAX_ATTEMPTS = 5
        val NOW: Instant = Instant.parse("2026-07-30T00:00:00Z")
        val LEASE: Duration = Duration.ofMinutes(1)
        val RETRY_DELAYS: List<Duration> =
            listOf(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
            )
    }
}
