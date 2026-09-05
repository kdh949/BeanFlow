package io.github.kdh949.beanflow.promotion.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class LimitedCouponCommandRetentionWorkerTest {
    @Test
    fun `one table failure is observable without skipping the other table and the next run retries`() {
        val repository = mock(LimitedCouponCommandRetentionRepository::class.java)
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-09-06T00:00:00Z")
        val worker = LimitedCouponCommandRetentionWorker(repository, Clock.fixed(now, ZoneOffset.UTC), registry)
        `when`(repository.purgeCampaignCommands(now, 100))
            .thenThrow(IllegalStateException("injected campaign cleanup failure"))
            .thenReturn(LimitedCouponCommandRetentionResult(1))
        `when`(repository.purgeClaimCommands(now, 100))
            .thenReturn(LimitedCouponCommandRetentionResult(2))

        assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException::class.java)
        verify(repository).purgeClaimCommands(now, 100)
        assertThat(
            registry
                .get("beanflow.promotion.limited_coupon.command_retention")
                .tag("table", "campaign")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)

        assertThat(worker.runOnce()).isEqualTo(3)
    }
}
