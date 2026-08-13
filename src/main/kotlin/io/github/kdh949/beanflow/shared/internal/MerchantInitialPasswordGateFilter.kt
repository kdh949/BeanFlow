package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal class MerchantInitialPasswordGateFilter(
    private val errorWriter: SecurityErrorResponseWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val actor = SecurityContextHolder.getContext().authentication?.principal as? MerchantActor
        if (actor?.accountState == MerchantAccountState.INITIAL_PASSWORD && !isAllowed(request)) {
            errorWriter.write(
                response,
                AuthenticationChain.MERCHANT,
                HttpServletResponse.SC_FORBIDDEN,
                "INITIAL_PASSWORD_CHANGE_REQUIRED",
                "Initial password must be changed before using merchant features",
                "initial_password_gate",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isAllowed(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return (request.method == "GET" && path == "/api/v1/merchant/me") ||
            (request.method == "POST" && path == "/api/v1/auth/merchant/password-changes")
    }
}
