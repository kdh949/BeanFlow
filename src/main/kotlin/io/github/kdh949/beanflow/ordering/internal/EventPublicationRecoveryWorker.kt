package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderAcceptedV1
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.StoreAcceptanceWarningRequestedV1
import io.github.kdh949.beanflow.operations.api.EventPublicationReprocessingCaseOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Component
internal class EventPublicationRecoveryWorker(
    private val publications: IncompleteEventPublications,
    private val compensationOperations: OrderCompensationOperations,
    private val compensationTargets: CompensationPublicationTargetRegistry,
    private val reprocessingCaseOperations: EventPublicationReprocessingCaseOperations,
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
                    if (isReservedAnalyticsTarget(publication.identifier)) {
                        return@withFilter false
                    }
                    val attempts = publication.completionAttempts
                    if (EventPublicationRetrySchedule.exhausted(attempts)) {
                        val event = publication.event
                        val compensationEvent = event is OrderRejectedV1 || event is OrderCancelledV1
                        val listenerId = listenerId(publication.identifier)
                        val stepType = if (compensationEvent) compensationTargets.find(event, listenerId) else null
                        val reason =
                            if (compensationEvent && stepType == null) {
                                "PUBLICATION_TARGET_UNMAPPED"
                            } else {
                                "EVENT_PUBLICATION_RETRY_EXHAUSTED"
                            }
                        reprocessingCaseOperations.openEventPublicationCase(
                            OpenReprocessingCaseCommand(
                                ownerReference = "event-publication:${publication.identifier}",
                                reason = reason,
                                correlationId = correlationId(event, publication.identifier.toString()),
                                now = now,
                            ),
                        )
                        if (stepType != null) {
                            compensationOperations.markPublicationManualReview(
                                compensationOrderId(event),
                                stepType,
                                "EVENT_PUBLICATION_RETRY_EXHAUSTED",
                                now,
                            )
                        }
                        meterRegistry
                            .counter(
                                "beanflow.event.publication.exhaustion.count",
                                "event_type",
                                event.javaClass.simpleName.lowercase(),
                                "outcome",
                                if (stepType == null && compensationEvent) "unmapped" else "manual_review",
                            ).increment()
                        if (compensationEvent && stepType == null) {
                            meterRegistry
                                .counter(
                                    "beanflow.order.termination.event.routing_error.count",
                                    "event_type",
                                    event.javaClass.simpleName.lowercase(),
                                    "consumer",
                                    "unmapped",
                                ).increment()
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

    private fun isReservedAnalyticsTarget(publicationId: UUID): Boolean =
        listenerId(publicationId).startsWith(RESERVED_ANALYTICS_TARGET_PREFIX)

    private fun listenerId(publicationId: UUID): String =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "select listener_id from event_publication where id = ?",
                String::class.java,
                publicationId,
            ),
        )

    private fun compensationOrderId(event: Any): UUID =
        when (event) {
            is OrderRejectedV1 -> event.orderId
            is OrderCancelledV1 -> event.orderId
            else -> error("Not an order compensation event")
        }

    private fun correlationId(
        event: Any,
        fallback: String,
    ): String =
        when (event) {
            is OrderRejectedV1 -> event.envelope.correlationId
            is OrderCancelledV1 -> event.envelope.correlationId
            is StoreAcceptanceWarningRequestedV1 -> event.envelope.correlationId
            is OrderAcceptedV1 -> event.envelope.correlationId
            is OrderReadyV1 -> event.envelope.correlationId
            is OrderCompletedV2 -> event.envelope.correlationId
            else -> fallback
        }

    private companion object {
        const val RESERVED_ANALYTICS_TARGET_PREFIX = "beanflow.analytics."
    }
}
