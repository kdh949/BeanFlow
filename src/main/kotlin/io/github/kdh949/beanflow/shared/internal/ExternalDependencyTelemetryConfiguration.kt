package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.ExternalDependencyCall
import io.github.kdh949.beanflow.shared.api.ExternalDependencyOutcome
import io.github.kdh949.beanflow.shared.api.ExternalDependencyTelemetry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class ExternalDependencyTelemetryConfiguration {
    @Bean
    fun externalDependencyTelemetry(meterRegistry: MeterRegistry): ExternalDependencyTelemetry =
        OpenTelemetryExternalDependencyTelemetry(
            meterRegistry,
            GlobalOpenTelemetry.getTracer("io.github.kdh949.beanflow.external"),
        )
}

internal class OpenTelemetryExternalDependencyTelemetry(
    private val meterRegistry: MeterRegistry,
    private val tracer: Tracer,
) : ExternalDependencyTelemetry {
    override fun <T> observe(
        call: ExternalDependencyCall,
        outcomeOf: (T) -> ExternalDependencyOutcome,
        block: () -> T,
    ): T {
        val provider = call.provider.tagValue
        val operation = call.operation.tagValue
        val span =
            tracer
                .spanBuilder("beanflow.$provider.$operation")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()
                .setAttribute("beanflow.provider", provider)
                .setAttribute("beanflow.operation", operation)
        val sample = Timer.start(meterRegistry)
        val scope = span.makeCurrent()
        return try {
            val result = block()
            val outcome = outcomeOf(result)
            complete(call, outcome, sample)
            span.setAttribute("beanflow.outcome", outcome.tagValue)
            if (outcome == ExternalDependencyOutcome.UNKNOWN || outcome == ExternalDependencyOutcome.FAILURE) {
                span.setStatus(StatusCode.ERROR)
            }
            result
        } catch (failure: Throwable) {
            complete(call, ExternalDependencyOutcome.FAILURE, sample)
            span.setAttribute("beanflow.outcome", ExternalDependencyOutcome.FAILURE.tagValue)
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            scope.close()
            span.end()
        }
    }

    private fun complete(
        call: ExternalDependencyCall,
        outcome: ExternalDependencyOutcome,
        sample: Timer.Sample,
    ) {
        val provider = call.provider.tagValue
        val operation = call.operation.tagValue
        val outcomeTag = outcome.tagValue
        meterRegistry
            .counter(
                "beanflow.external.calls",
                "provider",
                provider,
                "operation",
                operation,
                "outcome",
                outcomeTag,
            ).increment()
        sample.stop(
            meterRegistry.timer(
                "beanflow.external.duration",
                "provider",
                provider,
                "operation",
                operation,
                "outcome",
                outcomeTag,
            ),
        )
    }
}
