package io.github.kdh949.beanflow.support.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal data class SupportSubjectSearchRateWindowRetentionResult(
    val deletedCount: Int,
    val remainingBacklog: Long,
    val oldestRetainedWindowStartedAt: Instant?,
    val observedAt: Instant,
)

/** Deletes expired fixed-window rate state in cleanup-index order without exposing actor identifiers. */
@Service
internal class SupportSubjectSearchRateWindowRetention(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun purgeExpired(chunkSize: Int): SupportSubjectSearchRateWindowRetentionResult {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "Support search rate-window retention chunk size is invalid" }
        val batch =
            jdbcTemplate.queryForObject(
                """
                WITH retention_clock AS MATERIALIZED (
                    SELECT clock_timestamp() AS observed_at
                ), retention_context AS MATERIALIZED (
                    SELECT observed_at, observed_at - INTERVAL '24 hours' AS cutoff_at
                      FROM retention_clock
                ), candidates AS MATERIALIZED (
                    SELECT rate_window.actor_id, rate_window.window_started_at
                      FROM support_subject_search_rate_window rate_window
                      CROSS JOIN retention_context
                     WHERE rate_window.window_started_at < retention_context.cutoff_at
                     ORDER BY rate_window.window_started_at, rate_window.actor_id
                     FOR UPDATE OF rate_window SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM support_subject_search_rate_window rate_window
                     USING candidates
                     WHERE rate_window.actor_id = candidates.actor_id
                       AND rate_window.window_started_at = candidates.window_started_at
                    RETURNING rate_window.actor_id
                )
                SELECT retention_context.observed_at,
                       retention_context.cutoff_at,
                       count(deleted.actor_id) AS deleted_count
                  FROM retention_context
                  LEFT JOIN deleted ON true
                 GROUP BY retention_context.observed_at, retention_context.cutoff_at
                """.trimIndent(),
                { resultSet, _ ->
                    RetentionBatch(
                        deletedCount = resultSet.getInt("deleted_count"),
                        observedAt = resultSet.getTimestamp("observed_at").toInstant(),
                        cutoffAt = resultSet.getTimestamp("cutoff_at").toInstant(),
                    )
                },
                chunkSize,
            ) ?: error("Support search rate-window retention did not return a batch result")
        val state =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FILTER (WHERE window_started_at < ?) AS remaining_backlog,
                       min(window_started_at) AS oldest_retained_window_started_at
                  FROM support_subject_search_rate_window
                """.trimIndent(),
                { resultSet, _ ->
                    RetentionState(
                        remainingBacklog = resultSet.getLong("remaining_backlog"),
                        oldestRetainedWindowStartedAt =
                            resultSet.getTimestamp("oldest_retained_window_started_at")?.toInstant(),
                    )
                },
                Timestamp.from(batch.cutoffAt),
            ) ?: error("Support search rate-window retention did not return table state")
        return SupportSubjectSearchRateWindowRetentionResult(
            deletedCount = batch.deletedCount,
            remainingBacklog = state.remainingBacklog,
            oldestRetainedWindowStartedAt = state.oldestRetainedWindowStartedAt,
            observedAt = batch.observedAt,
        )
    }

    private data class RetentionBatch(
        val deletedCount: Int,
        val observedAt: Instant,
        val cutoffAt: Instant,
    )

    private data class RetentionState(
        val remainingBacklog: Long,
        val oldestRetainedWindowStartedAt: Instant?,
    )

    private companion object {
        const val MAX_CHUNK_SIZE = 1_000
    }
}

@Component
internal class SupportSubjectSearchRateWindowRetentionWorker(
    private val retention: SupportSubjectSearchRateWindowRetention,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.support-search-rate-retention.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val backlog = AtomicLong()
    private val oldestRetainedAgeSeconds = AtomicLong()

    init {
        meterRegistry.gauge("beanflow.support.search.rate_window.retention.backlog", backlog)
        meterRegistry.gauge(
            "beanflow.support.search.rate_window.retention.oldest_retained_age.seconds",
            oldestRetainedAgeSeconds,
        )
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.support-search-rate-retention.fixed-delay-ms:300000}",
        initialDelayString = "\${beanflow.support-search-rate-retention.initial-delay-ms:300000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int =
        try {
            val result = retention.purgeExpired(chunkSize)
            val oldestAge =
                result.oldestRetainedWindowStartedAt
                    ?.let { Duration.between(it, result.observedAt).seconds.coerceAtLeast(0) }
                    ?: 0
            backlog.set(result.remainingBacklog)
            oldestRetainedAgeSeconds.set(oldestAge)
            meterRegistry.counter(RUNS_METRIC, "outcome", "SUCCEEDED").increment()
            meterRegistry.counter("beanflow.support.search.rate_window.retention.deleted").increment(result.deletedCount.toDouble())
            if (result.deletedCount > 0 || result.remainingBacklog > 0) {
                logger.info(
                    "support_search_rate_window_retention outcome=SUCCEEDED deletedCount={} " +
                        "remainingBacklog={} oldestRetainedAgeSeconds={}",
                    result.deletedCount,
                    result.remainingBacklog,
                    oldestAge,
                )
            }
            result.deletedCount
        } catch (failure: RuntimeException) {
            meterRegistry.counter(RUNS_METRIC, "outcome", "FAILED").increment()
            meterRegistry.counter("beanflow.support.search.rate_window.retention.failures").increment()
            logger.error(
                "support_search_rate_window_retention outcome=FAILED failureType={}",
                failure.javaClass.simpleName,
            )
            0
        }

    private companion object {
        const val RUNS_METRIC = "beanflow.support.search.rate_window.retention.runs"
    }
}
