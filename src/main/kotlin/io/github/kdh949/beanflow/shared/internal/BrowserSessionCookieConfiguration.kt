package io.github.kdh949.beanflow.shared.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.web.http.CookieHttpSessionIdResolver
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.session.web.http.HttpSessionIdResolver
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
internal class BrowserSessionCookieConfiguration {
    @Bean(name = ["springSessionTransactionOperations"])
    fun springSessionTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        }

    @Bean
    fun httpSessionIdResolver(registry: AuthenticationPathRegistry): HttpSessionIdResolver =
        ActorPathHttpSessionIdResolver(
            registry = registry,
            customer = cookieResolver("BEANFLOW_CUSTOMER_SESSION"),
            merchant = cookieResolver("BEANFLOW_MERCHANT_SESSION"),
        )

    private fun cookieResolver(cookieName: String): CookieHttpSessionIdResolver =
        CookieHttpSessionIdResolver().apply {
            setCookieSerializer(
                DefaultCookieSerializer().apply {
                    setCookieName(cookieName)
                    setCookiePath("/")
                    setUseHttpOnlyCookie(true)
                    setUseSecureCookie(true)
                    setSameSite("Lax")
                },
            )
        }
}

internal class ActorPathHttpSessionIdResolver(
    private val registry: AuthenticationPathRegistry,
    private val customer: HttpSessionIdResolver,
    private val merchant: HttpSessionIdResolver,
) : HttpSessionIdResolver {
    override fun resolveSessionIds(request: HttpServletRequest): List<String> = delegate(request)?.resolveSessionIds(request).orEmpty()

    override fun setSessionId(
        request: HttpServletRequest,
        response: HttpServletResponse,
        sessionId: String,
    ) {
        delegate(request)?.setSessionId(request, response, sessionId)
    }

    override fun expireSession(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        delegate(request)?.expireSession(request, response)
    }

    private fun delegate(request: HttpServletRequest): HttpSessionIdResolver? =
        when (registry.classify(request.requestURI.removePrefix(request.contextPath))) {
            AuthenticationChain.CUSTOMER -> customer
            AuthenticationChain.MERCHANT -> merchant
            AuthenticationChain.PUBLIC, AuthenticationChain.OPERATIONS, null -> null
        }
}
