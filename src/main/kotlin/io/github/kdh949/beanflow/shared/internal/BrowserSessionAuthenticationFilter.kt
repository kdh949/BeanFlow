package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserAuthenticationInvalid
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.util.UUID

internal class BrowserSessionAuthenticationFilter(
    private val actorType: BrowserActorType,
    loaders: List<BrowserActorLoader>,
    private val clock: Clock,
    private val errorWriter: SecurityErrorResponseWriter,
    private val metrics: AuthenticationMetrics,
) : OncePerRequestFilter() {
    private val loader = loaders.singleOrNull { it.actorType == actorType }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val session =
            try {
                metrics.sessionLookup(actorType) { request.getSession(false) }
            } catch (_: RuntimeException) {
                metrics.sessionStoreError(actorType.name, "lookup")
                unavailable(response, "Session store lookup failed")
                return
            }
        if (session == null) {
            filterChain.doFilter(request, response)
            return
        }
        val actorId = session.getAttribute(ACTOR_ID_ATTRIBUTE) as? String
        val authenticatedAt = session.getAttribute(AUTHENTICATED_AT_ATTRIBUTE) as? Long
        val credentialVersion = session.getAttribute(CREDENTIAL_VERSION_ATTRIBUTE) as? Long
        if (actorId == null || authenticatedAt == null || credentialVersion == null) {
            if (!invalidate(session::invalidate, response)) return
            metrics.sessionLifecycle(actorType.name, "expire", "success")
            unauthorized(response, "Browser session attributes are invalid")
            return
        }
        if (isAbsolutelyExpired(actorType, authenticatedAt, clock.instant())) {
            if (!invalidate(session::invalidate, response)) return
            metrics.sessionLifecycle(actorType.name, "expire", "success")
            unauthorized(response, "Browser session expired")
            return
        }
        val currentLoader = loader
        if (currentLoader == null) {
            unavailable(response, "Browser account authentication is not available")
            return
        }
        try {
            val actor = currentLoader.load(UUID.fromString(actorId), credentialVersion)
            val expectedType =
                when (actorType) {
                    BrowserActorType.CUSTOMER -> actor is CustomerActor
                    BrowserActorType.MERCHANT -> actor is MerchantActor
                }
            if (!expectedType) {
                forbidden(response, "Loaded actor type does not match browser chain")
                return
            }
            SecurityContextHolder.getContext().authentication = BrowserSessionAuthenticationToken(actor)
            filterChain.doFilter(request, response)
        } catch (_: IllegalArgumentException) {
            if (!invalidate(session::invalidate, response)) return
            metrics.sessionLifecycle(actorType.name, "expire", "success")
            unauthorized(response, "Browser session actor ID is invalid")
        } catch (failure: BrowserAuthenticationInvalid) {
            invalidateRejectedCredential(session::invalidate)
            unauthorized(response, failure.message)
        } catch (failure: DomainFailure) {
            when (failure.code) {
                FailureCode.ACCESS_DENIED -> forbidden(response, failure.message)
                else -> unavailable(response, "Browser account validation failed")
            }
        } catch (_: RuntimeException) {
            metrics.sessionStoreError(actorType.name, "account_validation")
            unavailable(response, "Browser account validation failed")
        }
    }

    private fun invalidate(
        invalidate: () -> Unit,
        response: HttpServletResponse,
    ): Boolean =
        try {
            invalidate()
            true
        } catch (_: RuntimeException) {
            metrics.sessionStoreError(actorType.name, "delete")
            unavailable(response, "Session store deletion failed")
            false
        }

    private fun invalidateRejectedCredential(invalidate: () -> Unit) {
        try {
            invalidate()
            metrics.sessionLifecycle(actorType.name, "expire", "success")
        } catch (_: RuntimeException) {
            // The authoritative account/version check already rejected authentication. Physical
            // row deletion is retryable space cleanup and must never turn an invalid credential
            // into an authenticated request or obscure the stable 401 contract.
            metrics.sessionStoreError(actorType.name, "delete_rejected_credential")
        }
    }

    private fun unauthorized(
        response: HttpServletResponse,
        message: String,
    ) = errorWriter.write(
        response,
        authenticationChain(),
        HttpServletResponse.SC_UNAUTHORIZED,
        "UNAUTHORIZED",
        message,
        "invalid_or_expired_session",
    )

    private fun forbidden(
        response: HttpServletResponse,
        message: String,
    ) = errorWriter.write(
        response,
        authenticationChain(),
        HttpServletResponse.SC_FORBIDDEN,
        "ACCESS_DENIED",
        message,
        "actor_type_mismatch",
    )

    private fun unavailable(
        response: HttpServletResponse,
        message: String,
    ) = errorWriter.write(
        response,
        authenticationChain(),
        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        message,
        "dependency_unavailable",
    )

    private fun authenticationChain(): AuthenticationChain =
        when (actorType) {
            BrowserActorType.CUSTOMER -> AuthenticationChain.CUSTOMER
            BrowserActorType.MERCHANT -> AuthenticationChain.MERCHANT
        }
}
