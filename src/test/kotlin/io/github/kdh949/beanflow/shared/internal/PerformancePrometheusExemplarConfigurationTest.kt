package io.github.kdh949.beanflow.shared.internal

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

internal class PerformancePrometheusExemplarConfigurationTest {
    @Test
    fun `sampled OpenTelemetry context is exposed to Prometheus exemplars`() {
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        try {
            val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
            val span = openTelemetry.getTracer("beanflow-test").spanBuilder("request").startSpan()
            val bridge = PerformancePrometheusExemplarConfiguration().prometheusSpanContext()

            span.makeCurrent().use {
                assertThat(bridge.currentTraceId).isEqualTo(span.spanContext.traceId)
                assertThat(bridge.currentSpanId).isEqualTo(span.spanContext.spanId)
                assertThat(bridge.isCurrentSpanSampled).isTrue()
                bridge.markCurrentSpanAsExemplar()
            }
            span.end()

            assertThat(
                exporter.finishedSpanItems
                    .single()
                    .attributes
                    .get(AttributeKey.stringKey("exemplar")),
            ).isEqualTo("true")
        } finally {
            provider.close()
        }
    }

    @Test
    fun `Prometheus histogram scrape contains current trace and span exemplar`() {
        val provider = SdkTracerProvider.builder().build()
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        val span = openTelemetry.getTracer("beanflow-test").spanBuilder("request").startSpan()
        val registry =
            PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT,
                PrometheusRegistry(),
                Clock.SYSTEM,
                PerformancePrometheusExemplarConfiguration().prometheusSpanContext(),
            )
        try {
            val timer =
                Timer
                    .builder("http.server.requests")
                    .tag("uri", "/api/v1/test")
                    .publishPercentileHistogram()
                    .register(registry)

            span.makeCurrent().use { timer.record(Duration.ofMillis(25)) }

            val scrape = registry.scrape("application/openmetrics-text; version=1.0.0; charset=utf-8")
            assertThat(scrape).contains("trace_id=\"${span.spanContext.traceId}\"")
            assertThat(scrape).contains("span_id=\"${span.spanContext.spanId}\"")
        } finally {
            span.end()
            registry.close()
            provider.close()
        }
    }
}
