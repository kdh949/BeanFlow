package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
internal class SecurityErrorResponseWriter(
    private val objectMapper: ObjectMapper,
    private val correlationIdSource: CorrelationIdSource,
    private val metrics: AuthenticationMetrics,
) {
    fun write(
        response: HttpServletResponse,
        chain: AuthenticationChain,
        status: Int,
        code: String,
        message: String,
        reason: String,
    ) {
        metrics.securityFailure(chain, status, reason)
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to code,
                    "message" to message,
                    "correlationId" to correlationIdSource.currentOrCreate(),
                    "details" to emptyList<Any>(),
                ),
            ),
        )
    }
}

internal class ActorCredentialIsolationFilter(
    private val chain: AuthenticationChain,
    private val errorWriter: SecurityErrorResponseWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (hasCredentialForAnotherActor(request)) {
            errorWriter.write(
                response,
                chain,
                HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                "Credential actor type does not match this endpoint",
                "actor_credential_mismatch",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun hasCredentialForAnotherActor(request: HttpServletRequest): Boolean {
        val hasBearer = request.getHeader(HttpHeaders.AUTHORIZATION)?.startsWith("Bearer ", ignoreCase = true) == true
        val cookieNames =
            request.cookies
                .orEmpty()
                .map(Cookie::getName)
                .toSet()
        val hasCustomerSession = "BEANFLOW_CUSTOMER_SESSION" in cookieNames
        val hasMerchantSession = "BEANFLOW_MERCHANT_SESSION" in cookieNames
        return when (chain) {
            AuthenticationChain.PUBLIC -> false
            AuthenticationChain.OPERATIONS -> hasCustomerSession || hasMerchantSession
            AuthenticationChain.CUSTOMER -> hasBearer || (hasMerchantSession && !hasCustomerSession)
            AuthenticationChain.MERCHANT -> hasBearer || (hasCustomerSession && !hasMerchantSession)
        }
    }
}
