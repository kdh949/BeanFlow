package io.github.kdh949.beanflow.shared.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class JdbcParameterLoggingSafetyConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(JdbcParameterLoggingSafetyConfiguration::class.java)

    @Test
    fun `TRACE JDBC parameter logging fails application startup`() {
        withStatementLogger(Level.TRACE) {
            contextRunner.run { context ->
                assertThat(context.startupFailure)
                    .hasMessageContaining("JDBC parameter TRACE logging is forbidden because it can expose sensitive parameters")
            }
        }
    }

    @Suppress("DEPRECATION") // Logback marks ALL deprecated but it remains a deployable logger level.
    @Test
    fun `ALL JDBC parameter logging fails application startup`() {
        withStatementLogger(Level.ALL) {
            contextRunner.run { context ->
                assertThat(context.startupFailure)
                    .hasMessageContaining("JDBC parameter TRACE logging is forbidden because it can expose sensitive parameters")
            }
        }
    }

    @Test
    fun `DEBUG JDBC parameter logging permits application startup`() {
        withStatementLogger(Level.DEBUG) {
            contextRunner.run { context ->
                assertThat(context).hasNotFailed()
            }
        }
    }

    @Test
    fun `DEBUG HTTP request logging fails application startup`() {
        withLogger(SENSITIVE_HTTP_LOGGER, Level.DEBUG) {
            contextRunner.run { context ->
                assertThat(context.startupFailure)
                    .hasMessageContaining("HTTP request DEBUG logging is forbidden because it can expose sensitive request data")
            }
        }
    }

    private fun withStatementLogger(
        level: Level,
        block: () -> Unit,
    ) = withLogger(STATEMENT_CREATOR_UTILS_LOGGER, level, block)

    private fun withLogger(
        loggerName: String,
        level: Level,
        block: () -> Unit,
    ) {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val original = logger.level
        try {
            logger.level = level
            block()
        } finally {
            logger.level = original
        }
    }

    private companion object {
        const val STATEMENT_CREATOR_UTILS_LOGGER = "org.springframework.jdbc.core.StatementCreatorUtils"
        const val SENSITIVE_HTTP_LOGGER = "org.springframework.web.servlet.DispatcherServlet"
    }
}
