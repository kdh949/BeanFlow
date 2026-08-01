package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CursorHmacProperties::class)
internal class SignedCursorConfiguration {
    @Bean
    fun cursorMetrics(meterRegistry: MeterRegistry): CursorMetrics = CursorMetrics(meterRegistry)

    @Bean
    fun cursorHmacKeyRing(
        properties: CursorHmacProperties,
        metrics: CursorMetrics,
    ): CursorHmacKeyRing =
        try {
            CursorHmacKeyRing.from(properties).also { metrics.recordStartupValidation(CursorStartupOutcome.VALID) }
        } catch (exception: CursorKeyRingConfigurationException) {
            metrics.recordStartupValidation(CursorStartupOutcome.INVALID)
            throw IllegalStateException("Cursor HMAC configuration is invalid", exception)
        }

    @Bean
    fun signedCursorCodec(
        keyRing: CursorHmacKeyRing,
        clock: Clock,
        metrics: CursorMetrics,
    ): SignedCursorCodec = HmacSignedCursorCodec(keyRing, clock, metrics)
}
