package io.github.kdh949.beanflow.loyalty.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

internal class PartialRefundRestorationBoundaryTest {
    @Test
    fun `lot validity uses strict refund-success boundary at nanosecond precision`() {
        val succeededAt = Instant.parse("2026-08-01T00:00:00Z")

        assertThat(isPointLotValidAt(succeededAt.minusNanos(1), succeededAt)).isFalse()
        assertThat(isPointLotValidAt(succeededAt, succeededAt)).isFalse()
        assertThat(isPointLotValidAt(succeededAt.plusNanos(1), succeededAt)).isTrue()
    }
}
