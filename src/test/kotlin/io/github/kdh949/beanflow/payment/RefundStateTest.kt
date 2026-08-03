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
    fun `first claim requests refund and unknown permanently switches to lookup`() {
        val refund = refund()
        assertThat(refund.claim(UUID.randomUUID(), NOW, LEASE)).isEqualTo(RefundClaimMode.REQUEST)

        refund.recordUnknown("timeout", NOW)

        assertThat(refund.state).isEqualTo(RefundState.UNKNOWN)
        assertThat(refund.nextAttemptAt).isEqualTo(NOW.plusSeconds(10))
        assertThat(refund.claim(UUID.randomUUID(), NOW.plusSeconds(10), LEASE))
            .isEqualTo(RefundClaimMode.LOOKUP)
        assertThat(refund.requestAttemptCount).isEqualTo(1)
        assertThat(refund.lookupAttemptCount).isEqualTo(1)
    }

    @Test
    fun `expired request lease switches to due lookup rather than another request`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE)

        assertThatThrownBy { refund.recoverExpiredClaim(NOW.plusSeconds(59)) }
            .isInstanceOf(IllegalStateException::class.java)

        refund.recoverExpiredClaim(NOW.plusSeconds(60))
        assertThat(refund.state).isEqualTo(RefundState.UNKNOWN)
        assertThat(refund.claim(UUID.randomUUID(), NOW.plusSeconds(60), LEASE))
            .isEqualTo(RefundClaimMode.LOOKUP)
    }

    @Test
    fun `one unknown request and five unknown lookups end in manual review`() {
        val refund = refund()
        var now = NOW
        refund.claim(UUID.randomUUID(), now, LEASE)
        refund.recordUnknown("ack_lost", now)
        now = requireNotNull(refund.nextAttemptAt)

        repeat(Refund.LOOKUP_MAX_ATTEMPTS) {
            refund.claim(UUID.randomUUID(), now, LEASE)
            refund.recordUnknown("ack_lost", now)
            now = refund.nextAttemptAt ?: now
        }

        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.requestAttemptCount).isEqualTo(1)
        assertThat(refund.lookupAttemptCount).isEqualTo(5)
        assertThat(refund.attemptCount).isEqualTo(6)
    }

    @Test
    fun `explicit safe failures use the independent three request budget`() {
        val refund = refund()
        var now = NOW
        repeat(Refund.REQUEST_MAX_ATTEMPTS) {
            assertThat(refund.claim(UUID.randomUUID(), now, LEASE)).isEqualTo(RefundClaimMode.REQUEST)
            refund.recordRetryableRequestFailure("transport_rejected_before_send", now)
            now = refund.nextAttemptAt ?: now
        }

        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.requestAttemptCount).isEqualTo(3)
        assertThat(refund.lookupAttemptCount).isZero()
    }

    @Test
    fun `successful refund is terminal and records exact amount`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE)
        refund.succeed("provider-refund", NOW)

        assertThat(refund.state).isEqualTo(RefundState.SUCCEEDED)
        assertThat(refund.succeededAmountKrw).isEqualTo(7_000)
        assertThat(refund.providerRefundReference).isEqualTo("provider-refund")
        assertThatThrownBy { refund.claim(UUID.randomUUID(), NOW.plus(LEASE), LEASE) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `explicit terminal provider failure is not retried`() {
        val refund = refund()
        refund.claim(UUID.randomUUID(), NOW, LEASE)
        refund.fail("refund_declined", NOW)

        assertThat(refund.state).isEqualTo(RefundState.FAILED)
        assertThat(refund.lastFailureCode).isEqualTo("REFUND_DECLINED")
        assertThat(refund.nextAttemptAt).isNull()
    }

    @Test
    fun `operator reconciliation from failed uses one lookup without changing the automatic budget`() {
        val refund = refund(reason = "CUSTOMER_ORDER_CANCELLED")
        refund.claim(UUID.randomUUID(), NOW, LEASE)
        refund.fail("refund_declined", NOW)

        refund.scheduleOperatorReconciliation(NOW.plusSeconds(1))
        assertThat(refund.state).isEqualTo(RefundState.UNKNOWN)
        assertThat(refund.operatorReconciliationPending).isTrue()
        assertThat(refund.claim(UUID.randomUUID(), NOW.plusSeconds(1), LEASE))
            .isEqualTo(RefundClaimMode.LOOKUP)
        assertThat(refund.requestAttemptCount).isEqualTo(1)
        assertThat(refund.lookupAttemptCount).isZero()
        assertThatThrownBy { refund.recordUnknown("wrong_result_path", NOW.plusSeconds(1)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(refund.state).isEqualTo(RefundState.RECONCILING)

        refund.recordOperatorReconciliationUnknown("lookup_timeout", NOW.plusSeconds(1))
        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.operatorReconciliationPending).isFalse()
        assertThat(refund.nextAttemptAt).isNull()
        assertThat(refund.lookupAttemptCount).isZero()
    }

    @Test
    fun `expired operator lookup from manual review returns terminal without another provider budget`() {
        val refund = refund(reason = "CUSTOMER_ORDER_CANCELLED")
        var now = NOW
        refund.claim(UUID.randomUUID(), now, LEASE)
        refund.recordUnknown("ack_lost", now)
        now = requireNotNull(refund.nextAttemptAt)
        repeat(Refund.LOOKUP_MAX_ATTEMPTS) {
            refund.claim(UUID.randomUUID(), now, LEASE)
            refund.recordUnknown("ack_lost", now)
            now = refund.nextAttemptAt ?: now
        }
        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)

        val scheduledAt = now.plusSeconds(1)
        refund.scheduleOperatorReconciliation(scheduledAt)
        refund.claim(UUID.randomUUID(), scheduledAt, LEASE)
        refund.recoverExpiredClaim(scheduledAt.plus(LEASE))

        assertThat(refund.state).isEqualTo(RefundState.MANUAL_REVIEW)
        assertThat(refund.operatorReconciliationPending).isFalse()
        assertThat(refund.nextAttemptAt).isNull()
        assertThat(refund.requestAttemptCount).isEqualTo(1)
        assertThat(refund.lookupAttemptCount).isEqualTo(Refund.LOOKUP_MAX_ATTEMPTS)
    }

    private fun refund(reason: String = "STORE_ORDER_REJECTED"): Refund =
        Refund.request(
            id = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            requestedAmountKrw = 7_000,
            reason = reason,
            providerIdempotencyKey = "refund-key",
            sourceReference = "event:refund",
            now = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-30T00:00:00Z")
        val LEASE: Duration = Duration.ofMinutes(1)
    }
}
