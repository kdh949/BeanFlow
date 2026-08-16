package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class RefundPreviewVersionTest {
    private val orderId = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val paymentId = UUID.fromString("00000000-0000-4000-8000-0000000000b1")

    @Test
    fun `the same state always produces the same lowercase hex digest`() {
        val first = RefundPreviewVersion.compute(state())
        val second = RefundPreviewVersion.compute(state())

        assertThat(first).isEqualTo(second)
        assertThat(first).matches("[0-9a-f]{64}")
    }

    @Test
    fun `line order does not change the digest`() {
        val ascending = state(remaining = linkedMapOf(0 to 2L, 1 to 3L))
        val descending = state(remaining = linkedMapOf(1 to 3L, 0 to 2L))

        assertThat(RefundPreviewVersion.compute(ascending)).isEqualTo(RefundPreviewVersion.compute(descending))
    }

    @Test
    fun `every state input changes the digest`() {
        val baseline = RefundPreviewVersion.compute(state())

        val variants =
            listOf(
                state(orderAggregateVersion = 8),
                state(paymentVersion = 4),
                state(approvedAmountKrw = 9_999),
                state(succeededRefundAmountKrw = 1),
                state(unresolvedRefundCount = 1),
                state(restorationPolicyVersionId = 77),
                state(remaining = mapOf(0 to 1L, 1 to 3L)),
                state(remaining = mapOf(0 to 2L)),
            ).map(RefundPreviewVersion::compute)

        assertThat(variants).doesNotContain(baseline)
        assertThat(variants).doesNotHaveDuplicates()
    }

    @Test
    fun `a moved remaining quantity between lines changes the digest`() {
        val left = state(remaining = mapOf(0 to 1L, 1 to 2L))
        val right = state(remaining = mapOf(0 to 2L, 1 to 1L))

        assertThat(RefundPreviewVersion.compute(left)).isNotEqualTo(RefundPreviewVersion.compute(right))
    }

    private fun state(
        orderAggregateVersion: Long = 7,
        paymentVersion: Long = 3,
        approvedAmountKrw: Long = 10_000,
        succeededRefundAmountKrw: Long = 0,
        unresolvedRefundCount: Int = 0,
        restorationPolicyVersionId: Long = 42,
        remaining: Map<Int, Long> = mapOf(0 to 2L, 1 to 3L),
    ) = RefundPreviewState(
        orderId = orderId,
        orderAggregateVersion = orderAggregateVersion,
        paymentId = paymentId,
        paymentVersion = paymentVersion,
        approvedAmountKrw = approvedAmountKrw,
        succeededRefundAmountKrw = succeededRefundAmountKrw,
        unresolvedRefundCount = unresolvedRefundCount,
        restorationPolicyVersionId = restorationPolicyVersionId,
        remainingByLineSequence = remaining,
    )
}
