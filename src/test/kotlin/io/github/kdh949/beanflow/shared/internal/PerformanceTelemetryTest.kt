package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.PerformanceOperation
import io.github.kdh949.beanflow.shared.api.PerformanceStage
import io.github.kdh949.beanflow.shared.api.WorkerOwner
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class PerformanceTelemetryTest {
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var spans: InMemorySpanExporter
    private lateinit var provider: SdkTracerProvider
    private val now = Instant.parse("2026-09-13T00:00:30Z")

    @BeforeEach
    fun setUp() {
        meters = SimpleMeterRegistry()
        spans = InMemorySpanExporter.create()
        provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build()
    }

    @AfterEach
    fun tearDown() {
        provider.close()
        meters.close()
    }

    @Test
    fun `phase telemetry records only bounded operation stage and outcome`() {
        val telemetry = OpenTelemetryPerformancePhaseTelemetry(meters, tracer())

        assertThat(
            telemetry.observe(PerformanceOperation.ORDER_CREATE, PerformanceStage.IDEMPOTENCY_REGISTER) { "ok" },
        ).isEqualTo("ok")

        assertThat(
            meters
                .timer(
                    "beanflow.operation.phase.duration",
                    "operation",
                    "order_create",
                    "stage",
                    "idempotency_register",
                    "outcome",
                    "success",
                ).count(),
        ).isEqualTo(1)
        val span = spans.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("beanflow.order_create.idempotency_register")
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.operation"))).isEqualTo("order_create")
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.stage"))).isEqualTo("idempotency_register")
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.outcome"))).isEqualTo("success")
    }

    @Test
    fun `worker partial batch separates data read completed and failed items`() {
        val telemetry = OpenTelemetryWorkerTelemetry(meters, Clock.fixed(now, ZoneOffset.UTC), tracer())

        telemetry.observe(WorkerOwner.PAYMENT_RECONCILIATION) { run ->
            run.dataReadSucceeded()
            assertThat(
                meters
                    .get("beanflow.worker.data.last.success.timestamp.seconds")
                    .tag("owner", "payment_reconciliation")
                    .gauge()
                    .value(),
            ).isEqualTo(now.epochSecond.toDouble())
            run.claimed(3)
            run.claimLag(Duration.ofSeconds(4))
            run.completedAfter(Duration.ofMillis(20), 2)
            run.failed()
        }

        assertThat(meters.counter("beanflow.worker.runs", "owner", "payment_reconciliation", "outcome", "partial").count())
            .isEqualTo(1.0)
        assertThat(meters.counter("beanflow.worker.items", "owner", "payment_reconciliation", "outcome", "claimed").count())
            .isEqualTo(3.0)
        assertThat(meters.counter("beanflow.worker.items", "owner", "payment_reconciliation", "outcome", "completed").count())
            .isEqualTo(2.0)
        assertThat(meters.counter("beanflow.worker.items", "owner", "payment_reconciliation", "outcome", "failed").count())
            .isEqualTo(1.0)
        assertThat(
            meters
                .get("beanflow.worker.data.last.success.timestamp.seconds")
                .tag("owner", "payment_reconciliation")
                .gauge()
                .value(),
        ).isEqualTo(now.epochSecond.toDouble())
        assertThat(
            meters
                .get("beanflow.worker.business.last.success.timestamp.seconds")
                .tag("owner", "payment_reconciliation")
                .gauge()
                .value(),
        ).isZero()
        assertThat(
            meters
                .get("beanflow.worker.enqueue.to.claim.duration")
                .tag("owner", "payment_reconciliation")
                .timer()
                .count(),
        ).isEqualTo(1)
        assertThat(
            meters
                .get("beanflow.worker.claim.to.outcome.duration")
                .tag("owner", "payment_reconciliation")
                .tag("outcome", "completed")
                .timer()
                .count(),
        ).isEqualTo(1)
        assertThat(
            spans.finishedSpanItems
                .single()
                .attributes
                .get(AttributeKey.stringKey("beanflow.outcome")),
        ).isEqualTo("partial")
    }

    @Test
    fun `worker read failure is rethrown and not recorded as data success`() {
        val telemetry = OpenTelemetryWorkerTelemetry(meters, Clock.fixed(now, ZoneOffset.UTC), tracer())

        assertThatThrownBy {
            telemetry.observe(WorkerOwner.NOTIFICATION) { throw IllegalStateException("database unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(meters.counter("beanflow.worker.data.reads", "owner", "notification", "outcome", "failure").count())
            .isEqualTo(1.0)
        assertThat(
            meters
                .get("beanflow.worker.data.last.success.timestamp.seconds")
                .tag("owner", "notification")
                .gauge()
                .value(),
        ).isZero()
        assertThat(
            spans.finishedSpanItems
                .single()
                .status.statusCode,
        ).isEqualTo(StatusCode.ERROR)
    }

    private fun tracer() =
        OpenTelemetrySdk
            .builder()
            .setTracerProvider(provider)
            .build()
            .getTracer("beanflow-test")
}
