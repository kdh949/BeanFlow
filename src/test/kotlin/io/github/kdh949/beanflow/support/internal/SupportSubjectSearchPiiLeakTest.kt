package io.github.kdh949.beanflow.support.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Captures the full supported DEBUG request path and scans logs, metrics, Audit and response for PII canaries. */
@Import(TestcontainersConfiguration::class, SupportSubjectSearchIntegrationTest.SearchTestConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("subject search rate limiting persists in an independent transaction")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
        "logging.level.org.springframework.security.web.FilterChainProxy=INFO",
        "logging.level.org.springframework.test.web.servlet=INFO",
        "logging.level.org.springframework.web.servlet=INFO",
    ],
)
internal class SupportSubjectSearchPiiLeakTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
        private val blindIndexes: SupportSubjectSearchIntegrationTest.RecordingBlindIndexPort,
    ) {
        private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>()
        private var originalLevel: Level? = null

        @BeforeEach
        fun setUp() {
            blindIndexes.reset(ByteArray(32) { 77 })
            jdbcTemplate.execute(
                "TRUNCATE TABLE support_subject_search_rate_window, operations_audit_record, " +
                    "operations_operator_permission_grant CASCADE",
            )
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'SUPPORT_SUBJECT_SEARCH', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                ACTOR_ID,
                "support-pii-log-scan-grant:$ACTOR_ID",
            )
            originalLevel = rootLogger.level
            rootLogger.level = Level.DEBUG
            appender.list.clear()
            appender.start()
            rootLogger.addAppender(appender)
        }

        @AfterEach
        fun tearDown() {
            rootLogger.detachAppender(appender)
            rootLogger.level = originalLevel
            appender.stop()
        }

        @Test
        fun `raw normalized and protected exact-search values never enter output channels`() {
            val success =
                mockMvc
                    .perform(
                        post(BASE)
                            .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"criterion":{"type":"EMAIL","value":"$RAW_EMAIL"},"subjectTypes":["CUSTOMER"],"reasonCode":"PRIVACY_REQUEST"}""",
                            ),
                    ).andExpect(status().isOk)
                    .andReturn()
            val invalid =
                mockMvc
                    .perform(
                        post(BASE)
                            .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"criterion":{"type":"PHONE","value":"$RAW_INVALID_PHONE"},"subjectTypes":["CUSTOMER"],"reasonCode":"CASE_INTAKE"}""",
                            ),
                    ).andExpect(status().isBadRequest)
                    .andReturn()
            val queryRejected =
                mockMvc
                    .perform(
                        post("$BASE?value=$RAW_QUERY_EMAIL")
                            .with(jwt().jwt { it.subject(ACTOR_ID.toString()) })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"criterion":{"type":"EMAIL","value":"$QUERY_BODY_EMAIL"},"subjectTypes":["CUSTOMER"],"reasonCode":"CASE_INTAKE"}""",
                            ),
                    ).andExpect(status().isBadRequest)
                    .andReturn()

            val logs = appender.list.joinToString("\n", transform = ::renderedFully)
            val audits =
                jdbcTemplate
                    .queryForList(
                        "SELECT reason || '|' || before_summary || '|' || after_summary || '|' || source_reference " +
                            "FROM operations_audit_record",
                        String::class.java,
                    ).joinToString("\n")
            val metricTags = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }.joinToString("\n")
            val requestSnapshots =
                listOf(
                    SupportSearchCriterionRequest(ExactSearchCriterionType.EMAIL, RAW_EMAIL),
                    SearchSupportSubjectsRequest(
                        SupportSearchCriterionRequest(
                            ExactSearchCriterionType.EMAIL,
                            RAW_EMAIL,
                        ),
                        listOf(SupportSearchSubjectType.CUSTOMER),
                        SupportSearchReasonCode.PRIVACY_REQUEST,
                    ),
                ).joinToString("\n")
            val observed =
                listOf(
                    success.response.contentAsString,
                    invalid.response.contentAsString,
                    queryRejected.response.contentAsString,
                    logs,
                    audits,
                    metricTags,
                    requestSnapshots,
                )

            observed.forEach { channel -> assertThat(channel).doesNotContain(*CANARIES) }
            assertThat(SearchSupportSubjectsCommand::class.java.declaredMethods.map { it.name }).contains("toString")
        }

        private fun renderedFully(event: ILoggingEvent): String =
            buildString {
                append(event.formattedMessage.orEmpty())
                event.argumentArray?.forEach { append(' ').append(it) }
                event.mdcPropertyMap.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
                appendThrowable(event.throwableProxy)
            }

        private fun StringBuilder.appendThrowable(proxy: IThrowableProxy?) {
            var current = proxy
            val seen = mutableSetOf<IThrowableProxy>()
            while (current != null && seen.add(current)) {
                append(' ').append(current.className).append(' ').append(current.message.orEmpty())
                current.stackTraceElementProxyArray?.forEach { append(' ').append(it.steAsString) }
                current = current.cause
            }
        }

        private companion object {
            const val BASE = "/api/v1/support/searches"
            val ACTOR_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000099")
            const val RAW_EMAIL = "Private.User@Example.com"
            const val NORMALIZED_EMAIL = "private.user@example.com"
            const val RAW_INVALID_PHONE = "010-9876-5432 private"
            const val RAW_QUERY_EMAIL = "private-in-url@example.com"
            const val QUERY_BODY_EMAIL = "body@example.com"
            const val DIGEST_HEX =
                "4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d"
            val CANARIES =
                arrayOf(
                    RAW_EMAIL,
                    "Private.User@",
                    NORMALIZED_EMAIL,
                    RAW_INVALID_PHONE,
                    "010-9876-5432",
                    RAW_QUERY_EMAIL,
                    QUERY_BODY_EMAIL,
                    DIGEST_HEX,
                    "vault:v7:private",
                )
        }
    }
