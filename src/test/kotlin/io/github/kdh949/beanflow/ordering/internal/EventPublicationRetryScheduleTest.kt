package io.github.kdh949.beanflow.ordering.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EventPublicationRetryScheduleTest {
    private val publishedAt = Instant.parse("2026-07-30T00:00:00Z")

    @Test
    fun `retry becomes due at each exact schedule boundary`() {
        val delays = listOf(10L, 30L, 120L, 300L, 900L)

        delays.forEachIndexed { attempt, seconds ->
            assertThat(
                EventPublicationRetrySchedule.isDue(
                    attempt,
                    publishedAt,
                    null,
                    publishedAt.plusSeconds(seconds - 1),
                ),
            ).isFalse()
            assertThat(
                EventPublicationRetrySchedule.isDue(
                    attempt,
                    publishedAt,
                    null,
                    publishedAt.plusSeconds(seconds),
                ),
            ).isTrue()
        }
    }

    @Test
    fun `sixth completion attempt is exhausted`() {
        assertThat(EventPublicationRetrySchedule.exhausted(4)).isFalse()
        assertThat(EventPublicationRetrySchedule.exhausted(5)).isTrue()
    }

    @Test
    fun `last resubmission time starts the next delay`() {
        val lastAttempt = publishedAt.plusSeconds(100)

        assertThat(
            EventPublicationRetrySchedule.isDue(
                1,
                publishedAt,
                lastAttempt,
                lastAttempt.plusSeconds(29),
            ),
        ).isFalse()
        assertThat(
            EventPublicationRetrySchedule.isDue(
                1,
                publishedAt,
                lastAttempt,
                lastAttempt.plusSeconds(30),
            ),
        ).isTrue()
    }
}
