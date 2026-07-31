package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
internal class EventPublicationRecoveryWorker(
    private val publications: IncompleteEventPublications,
    private val compensationOperations: RejectionCompensationOperations,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.event-publication.batch-size:100}")
    private val batchSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pendingCount = gauge("beanflow.event.publication.pending.count")
    private val oldestAgeSeconds = gauge("beanflow.event.publication.oldest.age.seconds")
    private val maximumAttemptCount = gauge("beanflow.event.publication.attempt.max")

    @Scheduled(
        fixedDelayString = "\${beanflow.event-publication.fixed-delay-ms:10000}",
        initialDelayString = "\${beanflow.event-publication.initial-delay-ms:30000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce() {
        val now = clock.instant()
        publications.resubmitIncompletePublications(
            ResubmissionOptions
                .defaults()
                .withBatchSize(batchSize)
                .withMaxInFlight(batchSize)
                .withFilter { publication ->
                    val attempts = publication.completionAttempts
                    if (EventPublicationRetrySchedule.exhausted(attempts)) {
                        val event = publication.event
                        if (event is OrderRejectedV1) {
                            compensationOperations.markPublicationManualReview(
                                event.orderId,
                                "EVENT_PUBLICATION_RETRY_EXHAUSTED",
                                now,
                            )
                        }
                        logger.error(
                            "event_publication id={} eventType={} outcome=MANUAL_REVIEW attempts={}",
                            publication.identifier,
                            event.javaClass.name,
                            attempts,
                        )
                        false
                    } else {
                        EventPublicationRetrySchedule.isDue(
                            attempts,
                            publication.publicationDate,
                            publication.lastResubmissionDate,
                            now,
                        )
                    }
                },
        )
        updateMetrics(now)
    }

    private fun updateMetrics(now: Instant) {
        val count =
            jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null",
                Long::class.java,
            ) ?: 0
        val oldest =
            jdbcTemplate.queryForObject(
                "select min(publication_date) from event_publication where completion_date is null",
                Instant::class.java,
            )
        val attempts =
            jdbcTemplate.queryForObject(
                "select coalesce(max(completion_attempts), 0) from event_publication where completion_date is null",
                Long::class.java,
            ) ?: 0
        pendingCount.set(count)
        oldestAgeSeconds.set(
            oldest?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0,
        )
        maximumAttemptCount.set(attempts)
    }

    private fun gauge(name: String): AtomicLong = meterRegistry.gauge(name, AtomicLong(0))
}
