package io.github.kdh949.beanflow.shared.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

internal class CorrelationIdFilterTest {
    private val logger = LoggerFactory.getLogger(CorrelationIdFilter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val identifier = UUID.fromString("e94c497c-22d0-41f7-a282-3b5719687728")
    private val filter = CorrelationIdFilter(IdentifierSource { identifier })

    @BeforeEach
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun cleanup() {
        logger.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    @Test
    fun `security style early rejection gets a searchable code without logging URL or credentials`() {
        val request =
            MockHttpServletRequest("GET", "/api/v1/private-customer").apply {
                queryString = "token=private-token"
                addHeader("Authorization", "private-token")
                addHeader("X-Correlation-Id", "support-code")
            }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ ->
            assertThat(MDC.get("correlationId")).isEqualTo("support-code")
            response.status = 401
        }
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("support-code")
        val fields =
            appender.list
                .single()
                .keyValuePairs
                .associate { it.key to it.value }
        assertThat(fields).containsEntry("correlationId", "support-code").containsEntry("route", "UNMAPPED").containsEntry("status", 401)
        assertThat(fields.toString()).doesNotContain("private-token", "private-customer")
        assertThat(MDC.get("correlationId")).isNull()
    }

    @Test
    fun `unhandled exception is rethrown and logged once without its message`() {
        val failure = IllegalStateException("private-token")
        assertThatThrownBy {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, _ -> throw failure }
        }.isSameAs(failure)
        assertThat(appender.list).hasSize(1)
        val fields =
            appender.list
                .single()
                .keyValuePairs
                .associate { it.key to it.value }
        assertThat(fields).containsEntry("status", 500).containsEntry("exception_type", IllegalStateException::class.java.name)
        assertThat(fields.toString()).doesNotContain("private-token")
        assertThat(appender.list.single().throwableProxy).isNull()
        assertThat(MDC.get("correlationId")).isNull()
    }

    @Test
    fun `invalid header is replaced and previous thread context is restored`() {
        listOf("unsafe value", "x".repeat(129), "bad\r\nvalue").forEach { value ->
            MDC.put("correlationId", "outer-context")
            val request = MockHttpServletRequest().apply { addHeader("X-Correlation-Id", value) }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ -> assertThat(MDC.get("correlationId")).isEqualTo(identifier.toString()) }
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(identifier.toString())
            assertThat(MDC.get("correlationId")).isEqualTo("outer-context")
        }
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `active trace carries the response code and success has no failure event`() {
        val exporter = InMemorySpanExporter.create()
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().use { provider ->
            val span = provider.get("support-code-test").spanBuilder("request").startSpan()
            val response = MockHttpServletResponse()
            span.makeCurrent().use { filter.doFilter(MockHttpServletRequest(), response) { _, _ -> } }
            span.end()
            assertThat(
                exporter.finishedSpanItems.single().attributes.get(
                    io.opentelemetry.api.common.AttributeKey
                        .stringKey("beanflow.correlation_id"),
                ),
            ).isEqualTo(response.getHeader("X-Correlation-Id"))
        }
        assertThat(appender.list).isEmpty()
        assertThat(MDC.get("correlationId")).isNull()
    }
}
