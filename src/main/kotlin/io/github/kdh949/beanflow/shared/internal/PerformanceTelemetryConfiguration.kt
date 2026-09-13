package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.PerformanceOperation
import io.github.kdh949.beanflow.shared.api.PerformancePhaseTelemetry
import io.github.kdh949.beanflow.shared.api.PerformanceStage
import io.github.kdh949.beanflow.shared.api.WorkerItemOutcome
import io.github.kdh949.beanflow.shared.api.WorkerOwner
import io.github.kdh949.beanflow.shared.api.WorkerRun
import io.github.kdh949.beanflow.shared.api.WorkerTelemetry
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Configuration(proxyBeanMethods = false)
internal class PerformanceTelemetryConfiguration {
    @Bean
    fun performancePhaseTelemetry(meterRegistry: MeterRegistry): PerformancePhaseTelemetry =
        OpenTelemetryPerformancePhaseTelemetry(
            meterRegistry,
            GlobalOpenTelemetry.getTracer("io.github.kdh949.beanflow.performance.phase"),
        )

    @Bean
    fun workerTelemetry(
        meterRegistry: MeterRegistry,
        clock: Clock,
    ): WorkerTelemetry =
        OpenTelemetryWorkerTelemetry(
            meterRegistry,
            clock,
            GlobalOpenTelemetry.getTracer("io.github.kdh949.beanflow.worker"),
        )
}

internal class OpenTelemetryPerformancePhaseTelemetry(
    private val meterRegistry: MeterRegistry,
    private val tracer: Tracer,
) : PerformancePhaseTelemetry {
    override fun <T> observe(
        operation: PerformanceOperation,
        stage: PerformanceStage,
        block: () -> T,
    ): T {
        val span =
            tracer
                .spanBuilder("beanflow.${operation.tagValue}.${stage.tagValue}")
                .startSpan()
                .setAttribute("beanflow.operation", operation.tagValue)
                .setAttribute("beanflow.stage", stage.tagValue)
        val sample = Timer.start(meterRegistry)
        val scope = span.makeCurrent()
        var outcome = "success"
        return try {
            block()
        } catch (failure: Throwable) {
            outcome = "failure"
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            span.setAttribute("beanflow.outcome", outcome)
            sample.stop(
                Timer
                    .builder("beanflow.operation.phase.duration")
                    .publishPercentileHistogram()
                    .tags(
                        "operation",
                        operation.tagValue,
                        "stage",
                        stage.tagValue,
                        "outcome",
                        outcome,
                    ).register(meterRegistry),
            )
            scope.close()
            span.end()
        }
    }
}

internal class OpenTelemetryWorkerTelemetry(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val tracer: Tracer,
) : WorkerTelemetry {
    private val lastStarted = gauges("beanflow.worker.last.started.timestamp.seconds")
    private val lastDataSuccess = gauges("beanflow.worker.data.last.success.timestamp.seconds")
    private val lastBusinessSuccess = gauges("beanflow.worker.business.last.success.timestamp.seconds")

    init {
        WorkerOwner.entries.forEach { owner ->
            Gauge
                .builder("beanflow.worker.refresh.interval.seconds", owner) { it.refreshSeconds.toDouble() }
                .tag("owner", owner.tagValue)
                .strongReference(true)
                .register(meterRegistry)
        }
    }

    override fun <T> observe(
        owner: WorkerOwner,
        block: (WorkerRun) -> T,
    ): T {
        val startedAt = clock.instant().epochSecond
        val startedNanos = System.nanoTime()
        lastStarted.getValue(owner).set(startedAt)
        val state =
            MutableWorkerRun(
                dataReadSuccessRecorder = {
                    lastDataSuccess.getValue(owner).set(clock.instant().epochSecond)
                    meterRegistry.counter("beanflow.worker.data.reads", "owner", owner.tagValue, "outcome", "success").increment()
                },
                claimedRecorder = { count ->
                    recordClaimed(owner, count)
                },
                itemCountRecorder = { outcome, count ->
                    recordItems(owner, outcome.tagValue, count)
                },
                itemOutcomeRecorder = { outcome, duration, count ->
                    recordOutcome(owner, outcome, duration, count)
                },
                claimLagRecorder = { duration ->
                    Timer
                        .builder("beanflow.worker.enqueue.to.claim.duration")
                        .publishPercentileHistogram()
                        .tag("owner", owner.tagValue)
                        .register(meterRegistry)
                        .record(duration)
                },
            )
        val span =
            tracer
                .spanBuilder("beanflow.worker.${owner.tagValue}")
                .startSpan()
                .setAttribute("beanflow.owner", owner.tagValue)
        val scope = span.makeCurrent()
        var thrown = false
        return try {
            block(state)
        } catch (failure: Throwable) {
            thrown = true
            if (state.failures == 0) {
                state.runFailed()
            }
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            val outcome = state.outcome(thrown)
            val elapsed = System.nanoTime() - startedNanos
            meterRegistry.counter("beanflow.worker.runs", "owner", owner.tagValue, "outcome", outcome).increment()
            Timer
                .builder("beanflow.worker.run.duration")
                .tags("owner", owner.tagValue, "outcome", outcome)
                .register(meterRegistry)
                .record(elapsed, TimeUnit.NANOSECONDS)
            if (!state.readSucceeded) {
                meterRegistry.counter("beanflow.worker.data.reads", "owner", owner.tagValue, "outcome", "failure").increment()
            }
            if (outcome == "success") {
                lastBusinessSuccess.getValue(owner).set(clock.instant().epochSecond)
            }
            span
                .setAttribute("beanflow.outcome", outcome)
                .setAttribute("beanflow.claimed", state.claimed.toLong())
                .setAttribute("beanflow.completed", state.completed.toLong())
                .setAttribute("beanflow.failed", state.failures.toLong())
            scope.close()
            span.end()
        }
    }

    override fun recordClaimed(
        owner: WorkerOwner,
        count: Int,
    ) {
        require(count >= 0) { "Worker claimed count must not be negative" }
        recordItems(owner, "claimed", count)
    }

    override fun recordOutcome(
        owner: WorkerOwner,
        outcome: WorkerItemOutcome,
        duration: Duration,
        count: Int,
    ) {
        require(count >= 0) { "Worker outcome count must not be negative" }
        require(!duration.isNegative) { "Worker claim-to-outcome duration must not be negative" }
        recordItems(owner, outcome.tagValue, count)
        Timer
            .builder("beanflow.worker.claim.to.outcome.duration")
            .publishPercentileHistogram()
            .tags("owner", owner.tagValue, "outcome", outcome.tagValue)
            .register(meterRegistry)
            .record(duration)
    }

    private fun gauges(name: String): Map<WorkerOwner, AtomicLong> =
        WorkerOwner.entries.associateWith { owner ->
            requireNotNull(
                meterRegistry.gauge(
                    name,
                    listOf(
                        io.micrometer.core.instrument.Tag
                            .of("owner", owner.tagValue),
                    ),
                    AtomicLong(0),
                ),
            )
        }

    private fun recordItems(
        owner: WorkerOwner,
        outcome: String,
        count: Int,
    ) {
        if (count > 0) {
            meterRegistry.counter("beanflow.worker.items", "owner", owner.tagValue, "outcome", outcome).increment(count.toDouble())
        }
    }
}

private class MutableWorkerRun(
    private val dataReadSuccessRecorder: () -> Unit,
    private val claimedRecorder: (Int) -> Unit,
    private val itemCountRecorder: (WorkerItemOutcome, Int) -> Unit,
    private val itemOutcomeRecorder: (WorkerItemOutcome, Duration, Int) -> Unit,
    private val claimLagRecorder: (Duration) -> Unit,
) : WorkerRun {
    var readSucceeded = false
        private set
    var claimed = 0
        private set
    var completed = 0
        private set
    var failures = 0
        private set

    override fun dataReadSucceeded() {
        if (!readSucceeded) {
            readSucceeded = true
            dataReadSuccessRecorder()
        }
    }

    override fun claimed(count: Int) {
        require(count >= 0) { "Worker claimed count must not be negative" }
        claimed += count
        claimedRecorder(count)
    }

    override fun completed(count: Int) {
        require(count >= 0) { "Worker completed count must not be negative" }
        completed += count
        itemCountRecorder(WorkerItemOutcome.COMPLETED, count)
    }

    override fun completedAfter(
        duration: Duration,
        count: Int,
    ) {
        require(count >= 0) { "Worker completed count must not be negative" }
        completed += count
        itemOutcomeRecorder(WorkerItemOutcome.COMPLETED, duration, count)
    }

    override fun failed(count: Int) {
        require(count >= 0) { "Worker failure count must not be negative" }
        failures += count
        itemCountRecorder(WorkerItemOutcome.FAILED, count)
    }

    override fun failedAfter(
        duration: Duration,
        count: Int,
    ) {
        require(count >= 0) { "Worker failure count must not be negative" }
        failures += count
        itemOutcomeRecorder(WorkerItemOutcome.FAILED, duration, count)
    }

    override fun claimLag(duration: Duration) {
        claimLagRecorder(if (duration.isNegative) Duration.ZERO else duration)
    }

    fun runFailed() {
        failures++
    }

    fun outcome(thrown: Boolean): String =
        when {
            thrown || !readSucceeded || (failures > 0 && completed == 0) -> "failure"
            failures > 0 -> "partial"
            else -> "success"
        }
}
