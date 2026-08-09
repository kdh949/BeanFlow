package io.github.kdh949.beanflow.shared.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Refuses an effective TRACE/ALL override of Spring's JDBC parameter logger at startup.
 *
 * StatementCreatorUtils renders bound values at TRACE, including the request-only nearby-search
 * coordinate. A YAML default alone is not a privacy boundary because deployment configuration can
 * override it before the application starts.
 */
@Configuration(proxyBeanMethods = false)
internal class JdbcParameterLoggingSafetyConfiguration {
    @Bean
    fun jdbcParameterTraceLoggingGuard(): SmartInitializingSingleton =
        SmartInitializingSingleton {
            check(!LoggerFactory.getLogger(STATEMENT_CREATOR_UTILS_LOGGER).isTraceEnabled) {
                "JDBC parameter TRACE logging is forbidden because it can expose coordinates"
            }
        }

    private companion object {
        const val STATEMENT_CREATOR_UTILS_LOGGER = "org.springframework.jdbc.core.StatementCreatorUtils"
    }
}
