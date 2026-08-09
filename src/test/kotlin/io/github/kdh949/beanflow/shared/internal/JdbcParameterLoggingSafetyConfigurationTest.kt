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
                    .hasMessageContaining("JDBC parameter TRACE logging is forbidden because it can expose coordinates")
            }
        }
    }

    @Suppress("DEPRECATION") // Logback marks ALL deprecated but it remains a deployable logger level.
    @Test
    fun `ALL JDBC parameter logging fails application startup`() {
        withStatementLogger(Level.ALL) {
            contextRunner.run { context ->
                assertThat(context.startupFailure)
                    .hasMessageContaining("JDBC parameter TRACE logging is forbidden because it can expose coordinates")
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

    private fun withStatementLogger(
        level: Level,
        block: () -> Unit,
    ) {
        val logger = LoggerFactory.getLogger(STATEMENT_CREATOR_UTILS_LOGGER) as Logger
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
    }
}
