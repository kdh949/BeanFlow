package io.github.kdh949.beanflow.discovery.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.github.kdh949.beanflow.TestcontainersConfiguration
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * BR-28 / ADR-020 coordinate non-exposure, verified by capturing what the application actually
 * emits rather than by inspecting response bodies alone.
 *
 * A Logback appender is attached to the root logger for the whole request cycle, so every framework
 * and application event is examined: the formatted message, the raw argument array, the MDC and the
 * full throwable chain. The success path, the validation-failure path and the PostGIS-failure path
 * are all exercised, because an exception message is the easiest place for a coordinate to leak.
 *
 * There is no distributed tracer on the classpath, so there is no span to inspect; the MDC is the
 * only per-request diagnostic context that exists here, and it is asserted directly.
 */
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class NearbyCoordinatePrivacyIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
    ) {
        private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>()
        private var originalLevel: Level? = null

        @BeforeEach
        fun startCapturing() {
            jdbcTemplate.execute("DELETE FROM merchant_store_discovery_profile")
            jdbcTemplate.execute("DELETE FROM merchant_store")
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                STORE_ID,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_discovery_profile (store_id, name, location)
                VALUES (?, 'Privacy cafe', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography)
                """.trimIndent(),
                STORE_ID,
            )
            originalLevel = rootLogger.level
            // DEBUG is the most verbose level the application is operated at. Spring's JDBC
            // parameter logging sits at TRACE and is pinned off in application.yaml; that pinning
            // is asserted separately below.
            rootLogger.level = Level.DEBUG
            appender.list.clear()
            appender.start()
            rootLogger.addAppender(appender)
        }

        @AfterEach
        fun stopCapturing() {
            rootLogger.detachAppender(appender)
            appender.stop()
            rootLogger.level = originalLevel
        }

        @Test
        fun `no captured log event carries the customer coordinate on success, rejection or failure`() {
            mockMvc.perform(nearby()).andExpect(status().isOk)
            mockMvc.perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE)).andExpect(status().isOk)
            mockMvc
                .perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE, radiusMeters = "10001"))
                .andExpect(status().isBadRequest)

            installSpatialFailure()
            try {
                mockMvc
                    .perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE))
                    .andExpect(status().isServiceUnavailable)
            } finally {
                removeSpatialFailure()
            }

            val captured = appender.list.toList()
            assertThat(captured).isNotEmpty()
            val offenders =
                captured.filter { event -> SECRETS.any { secret -> renderedFully(event).contains(secret) } }
            assertThat(offenders.map { "${it.loggerName}: ${renderedFully(it).take(300)}" }).isEmpty()
        }

        @Test
        fun `the diagnostic context and the metric tags carry no coordinate`() {
            mockMvc.perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE)).andExpect(status().isOk)

            val mdcValues = appender.list.flatMap { it.mdcPropertyMap.entries.map { entry -> "${entry.key}=${entry.value}" } }
            assertThat(mdcValues).noneMatch { value -> SECRETS.any(value::contains) }

            val tagValues =
                meterRegistry
                    .find("beanflow.discovery.nearby.count")
                    .meters()
                    .flatMap { meter -> meter.id.tags.map { it.value } }
            assertThat(tagValues).isNotEmpty()
            assertThat(tagValues).noneMatch { value -> SECRETS.any(value::contains) }
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java),
            ).isZero()
        }

        /**
         * Characterises the one way a coordinate can still reach the log: Spring writes every bound
         * statement parameter at TRACE. `application.yaml` pins that logger to DEBUG so broad TRACE
         * logging cannot enable it by accident, but a deployment can still override the level, so
         * this is an operational constraint rather than a guarantee — the runbook says so too.
         *
         * The assertion is written both ways on purpose. If a future Spring version stops logging
         * parameter values, the first half fails and the constraint can be dropped instead of being
         * carried forever on an assumption. (This test context uses `src/test/resources`, which
         * shadows the production `application.yaml`, so it exercises the mechanism, not the pin.)
         */
        @Test
        fun `only JDBC parameter TRACE logging can still expose a coordinate and DEBUG does not`() {
            val statementLogger = LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils") as Logger

            statementLogger.level = Level.TRACE
            appender.list.clear()
            mockMvc.perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE)).andExpect(status().isOk)
            val atTrace = appender.list.any { event -> SECRETS.any { renderedFully(event).contains(it) } }

            statementLogger.level = Level.DEBUG
            appender.list.clear()
            mockMvc.perform(nearby(latitude = LEAKY_LATITUDE, longitude = LEAKY_LONGITUDE)).andExpect(status().isOk)
            val atDebug = appender.list.any { event -> SECRETS.any { renderedFully(event).contains(it) } }

            statementLogger.level = null
            assertThat(atTrace).`as`("parameter TRACE logging still writes bound coordinates").isTrue()
            assertThat(atDebug).`as`("DEBUG must never write a bound coordinate").isFalse()
        }

        /** Message, arguments, MDC and the whole throwable chain — everything an appender can see. */
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
                current.suppressed?.forEach { appendThrowable(it) }
                current = current.cause
            }
        }

        private fun installSpatialFailure() {
            jdbcTemplate.execute("ALTER TABLE merchant_store_discovery_profile RENAME TO discovery_profile_actual")
            jdbcTemplate.execute(
                """
                CREATE FUNCTION test_reject_discovery_profile()
                RETURNS SETOF discovery_profile_actual LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '58030', MESSAGE = 'injected spatial failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE VIEW merchant_store_discovery_profile AS SELECT * FROM test_reject_discovery_profile()",
            )
        }

        private fun removeSpatialFailure() {
            jdbcTemplate.execute(
                """
                DO ${'$'}${'$'}
                BEGIN
                    IF (SELECT relkind FROM pg_class
                         WHERE oid = to_regclass('merchant_store_discovery_profile')) = 'v' THEN
                        DROP VIEW merchant_store_discovery_profile;
                    END IF;
                    DROP FUNCTION IF EXISTS test_reject_discovery_profile();
                    IF to_regclass('discovery_profile_actual') IS NOT NULL THEN
                        ALTER TABLE discovery_profile_actual RENAME TO merchant_store_discovery_profile;
                    END IF;
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
        }

        private fun nearby(
            latitude: String = "37.5",
            longitude: String = "127.0",
            radiusMeters: String = "1000",
        ) = get("/api/v1/stores/nearby")
            .param("latitude", latitude)
            .param("longitude", longitude)
            .param("radiusMeters", radiusMeters)
            .with(customerJwt())

        private fun customerJwt(): RequestPostProcessor =
            jwt()
                .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private companion object {
            val STORE_ID: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")

            /**
             * Distinctive digit strings that cannot occur incidentally in a log line, so a match is
             * a real leak rather than a coincidence.
             */
            const val LEAKY_LATITUDE = "37.548271936"
            const val LEAKY_LONGITUDE = "127.019384726"
            val SECRETS = listOf(LEAKY_LATITUDE, LEAKY_LONGITUDE, "548271936", "019384726")
        }
    }
