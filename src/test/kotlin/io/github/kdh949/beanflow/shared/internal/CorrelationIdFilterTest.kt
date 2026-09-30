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
            assertThat(MDC.get("correlationId")).isEqualTo(identifier.toString())
            response.status = 401
        }
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(identifier.toString())
        val fields =
            appender.list
                .single()
                .keyValuePairs
                .associate { it.key to it.value }
        assertThat(
            fields,
        ).containsEntry("correlationId", identifier.toString()).containsEntry("route", "UNMAPPED").containsEntry("status", 401)
        assertThat(fields.toString()).doesNotContain("private-token", "private-customer", "support-code")
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
    fun `client controlled correlation headers never become support codes or trace fields`() {
        val inputs = listOf("37.123456789:127.987654321", "test-api-key.internal:42", "d94baf36-7c08-4ab1-b16f-54edfd4d9c35")
        var sequence = 0L
        val serverFilter = CorrelationIdFilter(IdentifierSource { UUID(0, ++sequence) })
        val codes = mutableListOf<String>()
        val exporter = InMemorySpanExporter.create()
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().use { provider ->
            inputs.forEach { input ->
                repeat(2) {
                    val request = MockHttpServletRequest("GET", "/api/v1/me/orders").apply { addHeader("X-Correlation-Id", input) }
                    val response = MockHttpServletResponse()
                    val span = provider.get("support-code-test").spanBuilder("request").startSpan()
                    span.makeCurrent().use {
                        serverFilter.doFilter(request, response) { _, _ ->
                            assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader("X-Correlation-Id"))
                            response.status = 503
                        }
                    }
                    span.end()
                    val code = response.getHeader("X-Correlation-Id")!!
                    assertThat(code).isNotEqualTo(input)
                    codes += code
                    assertThat(MDC.get("correlationId")).isNull()
                }
            }
            assertThat(
                exporter.finishedSpanItems.map {
                    it.attributes.get(
                        io.opentelemetry.api.common.AttributeKey
                            .stringKey("beanflow.correlation_id"),
                    )
                },
            ).containsExactlyElementsOf(codes)
        }
        assertThat(codes).hasSize(6).doesNotHaveDuplicates()
        assertThat(appender.list.map { event -> event.keyValuePairs.associate { it.key to it.value }["correlationId"] })
            .containsExactlyElementsOf(codes)
        assertThat(appender.list.joinToString { it.formattedMessage + it.keyValuePairs }).doesNotContain(*inputs.toTypedArray())
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
