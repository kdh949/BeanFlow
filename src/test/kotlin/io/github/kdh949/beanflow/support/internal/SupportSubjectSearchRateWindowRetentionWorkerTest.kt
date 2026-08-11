package io.github.kdh949.beanflow.support.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Instant

internal class SupportSubjectSearchRateWindowRetentionWorkerTest {
    @Test
    fun `worker records bounded PII-free retention metrics`() {
        val retention = mock<SupportSubjectSearchRateWindowRetention>()
        val registry = SimpleMeterRegistry()
        `when`(retention.purgeExpired(100))
            .thenReturn(
                SupportSubjectSearchRateWindowRetentionResult(
                    deletedCount = 3,
                    remainingBacklog = 7,
                    oldestRetainedWindowStartedAt = NOW.minusSeconds(25 * 60 * 60L),
                    observedAt = NOW,
                ),
            )
        val worker = SupportSubjectSearchRateWindowRetentionWorker(retention, registry, 100)

        assertThat(worker.runOnce()).isEqualTo(3)
        assertThat(registry.get("beanflow.support.search.rate_window.retention.deleted").counter().count()).isEqualTo(3.0)
        assertThat(registry.get("beanflow.support.search.rate_window.retention.backlog").gauge().value()).isEqualTo(7.0)
        assertThat(registry.get("beanflow.support.search.rate_window.retention.oldest_retained_age.seconds").gauge().value())
            .isEqualTo(25 * 60 * 60.0)
        assertThat(
            registry
                .get("beanflow.support.search.rate_window.retention.runs")
                .tag("outcome", "SUCCEEDED")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `worker failure remains independent and never logs exception content`() {
        val retention = mock<SupportSubjectSearchRateWindowRetention>()
        val registry = SimpleMeterRegistry()
        val logger = LoggerFactory.getLogger(SupportSubjectSearchRateWindowRetentionWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        `when`(retention.purgeExpired(100))
            .thenThrow(IllegalStateException("actor=private@example.com vault:v9:secret"))
        val worker = SupportSubjectSearchRateWindowRetentionWorker(retention, registry, 100)

        try {
            assertThat(worker.runOnce()).isZero()
            assertThat(
                registry
                    .get("beanflow.support.search.rate_window.retention.runs")
                    .tag("outcome", "FAILED")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
            assertThat(registry.get("beanflow.support.search.rate_window.retention.failures").counter().count()).isEqualTo(1.0)
            assertThat(appender.list).allSatisfy { event ->
                assertThat(event.level).isEqualTo(Level.ERROR)
                assertThat(event.formattedMessage)
                    .doesNotContain("private@example.com", "vault:v9", "actor=")
                    .contains("failureType=IllegalStateException")
                assertThat(event.throwableProxy).isNull()
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-11T12:00:00Z")
    }
}
