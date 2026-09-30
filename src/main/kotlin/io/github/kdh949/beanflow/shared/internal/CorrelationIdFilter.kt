package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class CorrelationIdFilter(
    private val identifierSource: IdentifierSource,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requested = request.getHeader(HEADER)
        val correlationId =
            requested
                ?.takeIf { it.length in 1..128 && it.all(::isSafeCharacter) }
                ?: identifierSource.next().toString()
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        Span.current().setAttribute("beanflow.correlation_id", correlationId)
        var escaped: Exception? = null
        try {
            filterChain.doFilter(request, response)
        } catch (failure: Exception) {
            escaped = failure
            throw failure
        } finally {
            try {
                val status = if (escaped != null) HttpServletResponse.SC_INTERNAL_SERVER_ERROR else response.status
                if (status >= 400) {
                    val event = if (status >= 500) failureLogger.atError() else failureLogger.atInfo()
                    val method = request.method.takeIf { it in METHODS } ?: "OTHER"
                    val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: "UNMAPPED"
                    val exceptionType = escaped?.javaClass?.name
                    event
                        .addKeyValue(MDC_KEY, correlationId)
                        .addKeyValue("method", method)
                        .addKeyValue("route", route)
                        .addKeyValue("status", status)
                        .addKeyValue("exception_type", exceptionType)
                        .log(
                            "http_request_failed correlationId={} method={} route={} status={} exception_type={}",
                            correlationId,
                            method,
                            route,
                            status,
                            exceptionType,
                        )
                }
            } finally {
                if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
            }
        }
    }

    private fun isSafeCharacter(character: Char): Boolean = character.isLetterOrDigit() || character in "-_.:"

    private companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"
        val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH")
        val failureLogger = LoggerFactory.getLogger(CorrelationIdFilter::class.java)
    }
}
