package io.github.kdh949.beanflow.ordering.internal

import java.time.Duration
import java.time.Instant

internal object EventPublicationRetrySchedule {
    private val delays =
        listOf(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
        )

    val maximumResubmissions: Int get() = delays.size

    // The candidate query uses this same policy before its batch limit.
    fun delaySecondsByAttempt(): List<Long> = listOf(delays.first().seconds) + delays.map { it.seconds }

    fun exhausted(completionAttempts: Int): Boolean = completionAttempts > delays.size

    fun isDue(
        completionAttempts: Int,
        publicationDate: Instant,
        lastResubmissionDate: Instant?,
        now: Instant,
    ): Boolean {
        require(!exhausted(completionAttempts)) { "Retry attempts are exhausted" }
        val reference = lastResubmissionDate ?: publicationDate
        val delayIndex = (completionAttempts.coerceAtLeast(1) - 1)
        return !now.isBefore(reference.plus(delays[delayIndex]))
    }
}
