package io.github.kdh949.beanflow.shared.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Refuses unsafe Spring JDBC and HTTP request logging overrides at startup.
 *
 * StatementCreatorUtils renders bound values at TRACE, including request-only nearby-search
 * coordinates and protected-profile blind indexes. Spring MVC and Security render the request body
 * or complete query URI at DEBUG. YAML defaults alone are not privacy boundaries because deployment
 * configuration can override them before application startup.
 */
@Configuration(proxyBeanMethods = false)
internal class JdbcParameterLoggingSafetyConfiguration {
    @Bean
    fun jdbcParameterTraceLoggingGuard(): SmartInitializingSingleton =
        SmartInitializingSingleton {
            check(!LoggerFactory.getLogger(STATEMENT_CREATOR_UTILS_LOGGER).isTraceEnabled) {
                "JDBC parameter TRACE logging is forbidden because it can expose sensitive parameters"
            }
            SENSITIVE_HTTP_LOGGERS.forEach { loggerName ->
                check(!LoggerFactory.getLogger(loggerName).isDebugEnabled) {
                    "HTTP request DEBUG logging is forbidden because it can expose sensitive request data"
                }
            }
        }

    private companion object {
        const val STATEMENT_CREATOR_UTILS_LOGGER = "org.springframework.jdbc.core.StatementCreatorUtils"
        val SENSITIVE_HTTP_LOGGERS =
            setOf(
                "org.springframework.security.web.FilterChainProxy",
                "org.springframework.web.servlet.DispatcherServlet",
                "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            )
    }
}
