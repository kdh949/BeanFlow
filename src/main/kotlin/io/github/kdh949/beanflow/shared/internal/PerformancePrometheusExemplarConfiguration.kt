package io.github.kdh949.beanflow.shared.internal

import io.opentelemetry.api.trace.Span
import io.prometheus.metrics.tracer.common.SpanContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("perf")
internal class PerformancePrometheusExemplarConfiguration {
    @Bean
    fun prometheusSpanContext(): SpanContext = OpenTelemetryPrometheusSpanContext
}

private object OpenTelemetryPrometheusSpanContext : SpanContext {
    override fun getCurrentTraceId(): String? = currentContext()?.traceId

    override fun getCurrentSpanId(): String? = currentContext()?.spanId

    override fun isCurrentSpanSampled(): Boolean = currentContext()?.isSampled ?: false

    override fun markCurrentSpanAsExemplar() {
        Span.current().setAttribute(SpanContext.EXEMPLAR_ATTRIBUTE_NAME, SpanContext.EXEMPLAR_ATTRIBUTE_VALUE)
    }

    private fun currentContext() = Span.current().spanContext.takeIf { it.isValid }
}
