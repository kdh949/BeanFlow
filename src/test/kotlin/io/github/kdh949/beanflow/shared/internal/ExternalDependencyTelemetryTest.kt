package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.ExternalDependencyCall
import io.github.kdh949.beanflow.shared.api.ExternalDependencyOperation
import io.github.kdh949.beanflow.shared.api.ExternalDependencyOutcome
import io.github.kdh949.beanflow.shared.api.ExternalProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
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

internal class ExternalDependencyTelemetryTest {
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var tracerProvider: SdkTracerProvider
    private lateinit var telemetry: OpenTelemetryExternalDependencyTelemetry

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        exporter = InMemorySpanExporter.create()
        tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        telemetry =
            OpenTelemetryExternalDependencyTelemetry(
                meterRegistry,
                openTelemetry.getTracer("io.github.kdh949.beanflow.test"),
            )
    }

    @AfterEach
    fun tearDown() {
        tracerProvider.close()
        meterRegistry.close()
    }

    @Test
    fun `successful external call records one bounded metric and client span`() {
        val result =
            telemetry.observe(
                ExternalDependencyCall(ExternalProvider.AISTOR, ExternalDependencyOperation.PUT),
            ) { "stored" }

        assertThat(result).isEqualTo("stored")
        assertThat(
            meterRegistry
                .counter(
                    "beanflow.external.calls",
                    "provider",
                    "aistor",
                    "operation",
                    "put",
                    "outcome",
                    "success",
                ).count(),
        ).isEqualTo(1.0)
        assertThat(
            meterRegistry
                .timer(
                    "beanflow.external.duration",
                    "provider",
                    "aistor",
                    "operation",
                    "put",
                    "outcome",
                    "success",
                ).count(),
        ).isEqualTo(1)

        val span = exporter.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("beanflow.aistor.put")
        assertThat(span.kind).isEqualTo(SpanKind.CLIENT)
        assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.provider"))).isEqualTo("aistor")
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.operation"))).isEqualTo("put")
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.outcome"))).isEqualTo("success")
    }

    @Test
    fun `unknown result is retained as a bounded error outcome`() {
        telemetry.observe(
            ExternalDependencyCall(ExternalProvider.TOSS, ExternalDependencyOperation.CONFIRM),
            outcomeOf = { ExternalDependencyOutcome.UNKNOWN },
        ) { "provider-response" }

        assertThat(
            meterRegistry
                .counter(
                    "beanflow.external.calls",
                    "provider",
                    "toss",
                    "operation",
                    "confirm",
                    "outcome",
                    "unknown",
                ).count(),
        ).isEqualTo(1.0)
        val span = exporter.finishedSpanItems.single()
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.outcome"))).isEqualTo("unknown")
    }

    @Test
    fun `exception is rethrown after recording failure without exception text labels`() {
        assertThatThrownBy {
            telemetry.observe(
                ExternalDependencyCall(ExternalProvider.VAULT, ExternalDependencyOperation.ENCRYPT),
            ) { throw IllegalStateException("private payload must not become a metric label") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(
            meterRegistry
                .counter(
                    "beanflow.external.calls",
                    "provider",
                    "vault",
                    "operation",
                    "encrypt",
                    "outcome",
                    "failure",
                ).count(),
        ).isEqualTo(1.0)
        val span = exporter.finishedSpanItems.single()
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.attributes.get(AttributeKey.stringKey("beanflow.outcome"))).isEqualTo("failure")
        assertThat(span.events).isEmpty()
    }
}
