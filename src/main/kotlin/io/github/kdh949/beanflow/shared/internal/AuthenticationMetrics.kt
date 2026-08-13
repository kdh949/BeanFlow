package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.stereotype.Component

@Component
internal class AuthenticationMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun securityFailure(
        chain: AuthenticationChain,
        status: Int,
        reason: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.authentication.failure.count",
                "chain",
                chain.name,
                "status",
                status.toString(),
                "reason",
                reason,
            ).increment()
    }

    fun sessionLifecycle(
        actorType: String,
        action: String,
        outcome: String,
        amount: Int = 1,
    ) {
        meterRegistry
            .counter(
                "beanflow.session.lifecycle.count",
                "actor_type",
                actorType,
                "action",
                action,
                "outcome",
                outcome,
            ).increment(amount.toDouble())
    }

    fun sessionStoreError(
        actorType: String,
        operation: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.session.store.error.count",
                "actor_type",
                actorType,
                "operation",
                operation,
            ).increment()
    }

    fun <T> sessionLookup(
        actorType: BrowserActorType,
        operation: () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        try {
            return operation()
        } finally {
            sample.stop(
                Timer
                    .builder("beanflow.session.lookup")
                    .tag("actor_type", actorType.name)
                    .publishPercentiles(0.5, 0.95)
                    .register(meterRegistry),
            )
        }
    }

    fun cleanup(outcome: String) {
        meterRegistry.counter("beanflow.session.cleanup.count", "outcome", outcome).increment()
    }
}

@Component
internal class ActiveBrowserSessionMetrics(
    meterRegistry: MeterRegistry,
    private val jdbcTemplate: JdbcTemplate,
) {
    init {
        BrowserActorType.entries.forEach { actorType ->
            Gauge
                .builder("beanflow.session.active", this) { metrics -> metrics.count(actorType) }
                .tag("actor_type", actorType.name)
                .register(meterRegistry)
        }
    }

    private fun count(actorType: BrowserActorType): Double =
        try {
            jdbcTemplate
                .queryForObject(
                    "SELECT count(*) FROM spring_session WHERE principal_name LIKE ? AND expiry_time > ?",
                    Long::class.java,
                    "${actorType.name}:%",
                    System.currentTimeMillis(),
                )?.toDouble() ?: Double.NaN
        } catch (_: RuntimeException) {
            Double.NaN
        }
}

@Component
internal class BrowserSessionCleanupWorker(
    private val sessions: JdbcIndexedSessionRepository,
    private val metrics: AuthenticationMetrics,
) {
    @Scheduled(cron = "\${beanflow.session.cleanup-cron:0 * * * * *}")
    fun cleanUpExpiredSessions() {
        try {
            sessions.cleanUpExpiredSessions()
            metrics.cleanup("success")
        } catch (failure: RuntimeException) {
            metrics.cleanup("failure")
            throw failure
        }
    }
}
