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

    fun exhausted(completionAttempts: Int): Boolean = completionAttempts !in delays.indices

    fun isDue(
        completionAttempts: Int,
        publicationDate: Instant,
        lastResubmissionDate: Instant?,
        now: Instant,
    ): Boolean {
        require(!exhausted(completionAttempts)) { "Retry attempts are exhausted" }
        val reference = lastResubmissionDate ?: publicationDate
        return !now.isBefore(reference.plus(delays[completionAttempts]))
    }
}
