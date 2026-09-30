package io.github.kdh949.beanflow.ordering.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.internal.CorrelationIdFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import java.util.UUID

internal class ApiFailureLoggingTest {
    private val appender =
        object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }
    private val loggers =
        listOf(
            ApiExceptionHandler::class.java,
            CorrelationIdFilter::class.java,
        ).map { LoggerFactory.getLogger(it) as Logger }
    private val handler = ApiExceptionHandler(CorrelationIdSource { MDC.get("correlationId") ?: REFERENCE })
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun prepare() {
        appender.start()
        loggers.forEach { it.addAppender(appender) }
        mvc =
            MockMvcBuilders
                .standaloneSetup(FailureController())
                .setControllerAdvice(handler)
                .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                    CorrelationIdFilter(
                        IdentifierSource {
                            UUID.fromString(REFERENCE)
                        },
                    ),
                ).build()
    }

    @AfterEach
    fun cleanup() {
        loggers.forEach { it.detachAppender(appender) }
        appender.stop()
        MDC.clear()
    }

    @Test
    fun `wrapped database failure links response logs and template without leaking SQL or input`() {
        mvc
            .perform(
                get(
                    "/diagnostics/private-order/database",
                ).queryParam("ticket", SECRET).header("Authorization", SECRET).header("X-Correlation-Id", CLIENT_REFERENCE),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(header().string("X-Correlation-Id", REFERENCE))
            .andExpect(jsonPath("$.correlationId").value(REFERENCE))
            .andExpect(jsonPath("$.message").value("Customer order read dependency is unavailable"))

        val diagnosis = diagnostic()
        assertThat(diagnosis["error_code"]).isEqualTo("DEPENDENCY_UNAVAILABLE")
        assertThat(diagnosis["sql_state"]).isEqualTo("42703")
        assertThat(diagnosis["exception_types"].toString()).contains("DomainFailure", "BadSqlGrammarException", "SQLException")
        assertThat(diagnosis["exception_frames"].toString()).contains("FailureController.database")
        val request = appender.list.single { it.formattedMessage.startsWith("http_request_failed ") }
        assertThat(fields(request)).containsEntry("correlationId", REFERENCE).containsEntry("route", "/diagnostics/{reference}/database")
        assertThat(fields(request)).containsEntry("status", 503).containsEntry("method", "GET")
        assertThat(appender.list).hasSize(2).allSatisfy { event ->
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.mdcPropertyMap).containsEntry("correlationId", REFERENCE)
            assertThat(event.throwableProxy).isNull()
            assertThat(
                event.formattedMessage + fields(event) + event.mdcPropertyMap,
            ).doesNotContain(SECRET, "private-order", "SELECT", "ticket", CLIENT_REFERENCE)
        }
        assertThat(MDC.get("correlationId")).isNull()
    }

    @Test
    fun `projection failure without a cause still records its source`() {
        mvc.perform(get("/diagnostics/private-order/projection")).andExpect(status().isServiceUnavailable)
        assertThat(diagnostic()["exception_types"].toString()).contains("DomainFailure").doesNotContain("SQLException")
        assertThat(diagnostic()["exception_frames"].toString()).contains("FailureController.projection")
        assertThat(diagnostic()["sql_state"]).isNull()
    }

    @Test
    fun `direct persistence exception retains a generic response and safe diagnostics`() {
        mvc
            .perform(get("/diagnostics/private-order/persistence"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.message").value("A required persistence dependency is unavailable"))
        assertThat(diagnostic()["exception_types"].toString()).contains("DataAccessResourceFailureException")
        assertThat(diagnostic().toString()).doesNotContain(SECRET)
    }

    @Test
    fun `business conflict records HTTP failure without a dependency error`() {
        mvc.perform(get("/diagnostics/private-order/conflict")).andExpect(status().isConflict)
        assertThat(appender.list).hasSize(1)
        assertThat(appender.list.single().level).isEqualTo(Level.INFO)
        assertThat(fields(appender.list.single())).containsEntry("status", 409)
    }

    @Test
    fun `invalid sqlstate and suppressed exceptions never enter diagnostics`() {
        val cause = SQLException(SECRET, SECRET)
        cause.addSuppressed(IllegalStateException(SECRET))
        handler.persistenceFailure(DataAccessResourceFailureException(SECRET, cause))
        assertThat(diagnostic()["sql_state"]).isNull()
        assertThat(diagnostic().toString()).doesNotContain(SECRET, "IllegalStateException")
        assertThat(appender.list.single().throwableProxy).isNull()
    }

    @Test
    fun `deep and cyclic causes are bounded`() {
        val cycle = IllegalStateException(SECRET)
        val cycleParent = RuntimeException(SECRET, cycle)
        cycle.initCause(cycleParent)
        handler.persistenceFailure(DataAccessResourceFailureException(SECRET, cycle))
        assertThat(diagnostic()["exception_chain_truncated"]).isEqualTo(true)
        assertThat(diagnostic()["exception_types"] as List<*>).hasSize(3)
        appender.list.clear()
        var cause: Throwable = SQLException(SECRET, "08006")
        repeat(20) { cause = RuntimeException(SECRET, cause) }
        handler.persistenceFailure(DataAccessResourceFailureException(SECRET, cause))
        assertThat(diagnostic()["exception_chain_truncated"]).isEqualTo(true)
        assertThat(diagnostic()["exception_types"] as List<*>).hasSize(8)
        assertThat(diagnostic()["exception_frames"] as List<*>).allSatisfy { assertThat(it as List<*>).hasSizeLessThanOrEqualTo(12) }
    }

    private fun diagnostic() = fields(appender.list.single { it.formattedMessage.startsWith("api_dependency_failed ") })

    private fun fields(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs.orEmpty().associate { it.key to it.value }

    @RestController
    internal class FailureController {
        @GetMapping("/diagnostics/{reference}/database")
        fun database(): Nothing =
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer order read dependency is unavailable").also {
                it.initCause(BadSqlGrammarException(SECRET, "SELECT $SECRET", SQLException(SECRET, "42703")))
            }

        @GetMapping("/diagnostics/{reference}/projection")
        fun projection(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer order projection is invalid")

        @GetMapping("/diagnostics/{reference}/persistence")
        fun persistence(): Nothing = throw DataAccessResourceFailureException(SECRET)

        @GetMapping("/diagnostics/{reference}/conflict")
        fun conflict(): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, SECRET)
    }

    private companion object {
        const val CLIENT_REFERENCE = "37.123456789:127.987654321"
        const val SECRET = "secret-authkey-raw-customer-data"
        const val REFERENCE = "7ecac34c-e7f6-4723-a72f-c5895c0ff4af"
    }
}
