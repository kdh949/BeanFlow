package io.github.kdh949.beanflow.shared.internal

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

internal class PerformanceTelemetryContextFilterTest {
    private val filter = PerformanceTelemetryContextFilter()

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `bounded perf metadata is available only while the request is processed`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader("X-BeanFlow-Test-Id", "smoke-20260902.1")
                addHeader("X-BeanFlow-Scenario", "quote-order")
            }
        val response = MockHttpServletResponse()
        var capturedTestId: String? = null
        var capturedScenario: String? = null
        val chain =
            FilterChain { _, _ ->
                capturedTestId = MDC.get("beanflow_test_id")
                capturedScenario = MDC.get("beanflow_scenario")
            }

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(200)
        assertThat(capturedTestId).isEqualTo("smoke-20260902.1")
        assertThat(capturedScenario).isEqualTo("quote-order")
        assertThat(MDC.get("beanflow_test_id")).isNull()
        assertThat(MDC.get("beanflow_scenario")).isNull()
    }

    @Test
    fun `missing paired header and unbounded values are rejected before application handling`() {
        listOf(
            mapOf("X-BeanFlow-Test-Id" to "only-id"),
            mapOf("X-BeanFlow-Test-Id" to "bad value", "X-BeanFlow-Scenario" to "quote-order"),
            mapOf("X-BeanFlow-Test-Id" to "valid", "X-BeanFlow-Scenario" to "customer-123"),
            mapOf("X-BeanFlow-Test-Id" to "x".repeat(65), "X-BeanFlow-Scenario" to "db-lock"),
        ).forEach { headers ->
            val request = MockHttpServletRequest()
            headers.forEach(request::addHeader)
            val response = MockHttpServletResponse()
            var invoked = false

            filter.doFilter(request, response) { _, _ -> invoked = true }

            assertThat(response.status).isEqualTo(400)
            assertThat(invoked).isFalse()
        }
    }

    @Test
    fun `ordinary request without perf metadata is unchanged`() {
        val response = MockHttpServletResponse()
        var invoked = false

        filter.doFilter(MockHttpServletRequest(), response) { _, _ -> invoked = true }

        assertThat(response.status).isEqualTo(200)
        assertThat(invoked).isTrue()
    }
}
