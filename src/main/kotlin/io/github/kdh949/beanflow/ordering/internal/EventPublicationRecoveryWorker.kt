package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val queries: EventPublicationRecoveryQueries,
    private val manualReview: EventPublicationManualReviewService,
    private val scope: AutomaticPublicationRecoveryScope,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.event-publication.batch-size:100}")
    private val batchSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pendingCount = gauge("beanflow.event.publication.pending.count")
    private val oldestAgeSeconds = gauge("beanflow.event.publication.oldest.age.seconds")
    private val maximumAttemptCount = gauge("beanflow.event.publication.attempt.max")
    private val retryPendingCount = gauge("beanflow.event.publication.retry.pending.count")
    private val manualReviewPendingCount = gauge("beanflow.event.publication.manual.review.pending.count")
    private val manualReviewOldestAgeSeconds = gauge("beanflow.event.publication.manual.review.oldest.age.seconds")

    init {
        require(batchSize > 0) { "Event publication recovery batch size must be positive" }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.event-publication.fixed-delay-ms:10000}",
        initialDelayString = "\${beanflow.event-publication.initial-delay-ms:30000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce() {
        val now = clock.instant()
        queries.findExhaustedIds(batchSize).forEach { id ->
            // The service proxy commits the case and compensation step before telemetry is emitted.
            manualReview.transition(id, now)?.let(::recordTransition)
        }
        scope.run(now) {
            publications.resubmitIncompletePublications(
                ResubmissionOptions.defaults().withBatchSize(batchSize).withMaxInFlight(batchSize),
            )
        }
        updateMetrics(now)
    }

    private fun recordTransition(transition: PublicationManualReviewTransition) {
        meterRegistry
            .counter(
                "beanflow.event.publication.exhaustion.count",
                "event_type",
                transition.eventType.lowercase(),
                "outcome",
                if (transition.unmapped) "unmapped" else "manual_review",
            ).increment()
        if (transition.unmapped) {
            meterRegistry
                .counter(
                    "beanflow.order.termination.event.routing_error.count",
                    "event_type",
                    transition.eventType.lowercase(),
                    "consumer",
                    "unmapped",
                ).increment()
        }
        logger.error(
            "event_publication publicationId={} eventId={} eventType={} listenerId={} correlationId={} " +
                "caseId={} outcome=MANUAL_REVIEW attempts={} reason={}",
            transition.publicationId,
            transition.eventId,
            transition.eventType,
            transition.listenerId,
            transition.correlationId,
            transition.caseId,
            transition.attempts,
            transition.reason,
        )
    }

    private fun updateMetrics(now: Instant) {
        val backlog = queries.backlog()
        pendingCount.set(backlog.pending)
        oldestAgeSeconds.set(ageSeconds(backlog.oldest, now))
        maximumAttemptCount.set(backlog.attempts)
        retryPendingCount.set(backlog.retryPending)
        val reviewBacklog = queries.manualReviewBacklog()
        manualReviewPendingCount.set(reviewBacklog.pending)
        manualReviewOldestAgeSeconds.set(ageSeconds(reviewBacklog.oldest, now))
    }

    private fun ageSeconds(
        oldest: Instant?,
        now: Instant,
    ): Long = oldest?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0

    private fun gauge(name: String): AtomicLong = requireNotNull(meterRegistry.gauge(name, AtomicLong(0)))
}
