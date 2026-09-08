package io.github.kdh949.beanflow.shared.internal

import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Profile("perf")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
internal class PerformanceTelemetryContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val testId = request.getHeader(TEST_ID_HEADER)
        val scenario = request.getHeader(SCENARIO_HEADER)
        if (testId == null && scenario == null) {
            filterChain.doFilter(request, response)
            return
        }
        if (testId == null || scenario == null || !TEST_ID_PATTERN.matches(testId) || scenario !in SCENARIOS) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid performance telemetry metadata")
            return
        }

        val testIdScope = MDC.putCloseable(TEST_ID_MDC, testId)
        val scenarioScope = MDC.putCloseable(SCENARIO_MDC, scenario)
        Span
            .current()
            .setAttribute("beanflow.test.id", testId)
            .setAttribute("beanflow.scenario", scenario)
        try {
            filterChain.doFilter(request, response)
        } finally {
            scenarioScope.close()
            testIdScope.close()
        }
    }

    private companion object {
        const val TEST_ID_HEADER = "X-BeanFlow-Test-Id"
        const val SCENARIO_HEADER = "X-BeanFlow-Scenario"
        const val TEST_ID_MDC = "beanflow_test_id"
        const val SCENARIO_MDC = "beanflow_scenario"
        val TEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SCENARIOS =
            setOf(
                "quote-order",
                "idempotency",
                "board-polling",
                "toss-success",
                "toss-decline",
                "toss-timeout",
                "toss-unknown",
                "aistor",
                "vault",
                "db-lock",
            )
    }
}
