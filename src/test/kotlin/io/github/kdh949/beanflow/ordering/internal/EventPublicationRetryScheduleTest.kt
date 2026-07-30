package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EventPublicationRetryScheduleTest {
    private val publishedAt = Instant.parse("2026-07-30T00:00:00Z")

    @Test
    fun `retry becomes due at each exact schedule boundary`() {
        val delays = listOf(10L, 30L, 120L, 300L, 900L)

        delays.forEachIndexed { attemptIndex, seconds ->
            val completionAttempts = attemptIndex + 1
            assertThat(
                EventPublicationRetrySchedule.isDue(
                    completionAttempts,
                    publishedAt,
                    null,
                    publishedAt.plusSeconds(seconds - 1),
                ),
            ).isFalse()
            assertThat(
                EventPublicationRetrySchedule.isDue(
                    completionAttempts,
                    publishedAt,
                    null,
                    publishedAt.plusSeconds(seconds),
                ),
            ).isTrue()
        }
    }

    @Test
    fun `publication is exhausted after five resubmission failures`() {
        assertThat(EventPublicationRetrySchedule.exhausted(5)).isFalse()
        assertThat(EventPublicationRetrySchedule.exhausted(6)).isTrue()
    }

    @Test
    fun `publication left incomplete before initial listener invocation uses first delay`() {
        assertThat(
            EventPublicationRetrySchedule.isDue(
                0,
                publishedAt,
                null,
                publishedAt.plusSeconds(9),
            ),
        ).isFalse()
        assertThat(
            EventPublicationRetrySchedule.isDue(
                0,
                publishedAt,
                null,
                publishedAt.plusSeconds(10),
            ),
        ).isTrue()
    }

    @Test
    fun `last resubmission time starts the next delay`() {
        val lastAttempt = publishedAt.plusSeconds(100)

        assertThat(
            EventPublicationRetrySchedule.isDue(
                2,
                publishedAt,
                lastAttempt,
                lastAttempt.plusSeconds(29),
            ),
        ).isFalse()
        assertThat(
            EventPublicationRetrySchedule.isDue(
                2,
                publishedAt,
                lastAttempt,
                lastAttempt.plusSeconds(30),
            ),
        ).isTrue()
    }
}
