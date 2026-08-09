package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The two hand-controlled clocks must never report an instant PostgreSQL cannot store.
 *
 * Both drive tests that write work at `now` and then claim it with `nextAttemptAt <= now` without
 * moving the clock. A `timestamptz` is rounded to microseconds on the way in, so a finer instant can
 * come back later than the clock still reports and the work is never due again.
 *
 * The time source is supplied here rather than read from the host: `Instant.now()` is
 * microsecond-aligned on macOS and nanosecond-precise on Linux, so a test that took the real clock
 * would assert nothing on a developer machine and only fail in CI — which is exactly how the
 * regression this guards against was found.
 */
internal class FixedTestClockPrecisionTest {
    private val nanosecondPrecise: Instant = Instant.parse("2026-08-09T00:00:00Z").plusNanos(999)
    private val storable: Instant = nanosecondPrecise.truncatedTo(ChronoUnit.MICROS)

    @Test
    fun `the payment deadline clock drops precision the store cannot keep`() {
        val clock = PickupSlotPaymentDeadlineTestClock { nanosecondPrecise }

        assertThat(clock.instant()).isEqualTo(storable).isNotEqualTo(nanosecondPrecise)

        clock.reset()
        assertThat(clock.instant()).isEqualTo(storable)

        clock.set(nanosecondPrecise)
        assertThat(clock.instant()).isEqualTo(storable)
    }

    @Test
    fun `the publication recovery clock drops precision the store cannot keep`() {
        val clock = PublicationRecoveryTestClock { nanosecondPrecise }

        assertThat(clock.instant()).isEqualTo(storable).isNotEqualTo(nanosecondPrecise)

        clock.reset()
        assertThat(clock.instant()).isEqualTo(storable)
    }
}
