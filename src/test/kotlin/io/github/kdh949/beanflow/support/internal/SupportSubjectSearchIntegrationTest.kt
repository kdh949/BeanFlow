package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.KeyedBlindIndexPort
import io.github.kdh949.beanflow.shared.api.NormalizedExactSearchValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class, SupportSubjectSearchIntegrationTest.SearchTestConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SupportSubjectSearchIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val blindIndexes: RecordingBlindIndexPort,
        private val preflight: SupportSubjectSearchPreflight,
        private val searchTransaction: SupportSubjectSearchTransaction,
        private val identifiers: IdentifierSource,
        private val rateWindowRetention: SupportSubjectSearchRateWindowRetention,
    ) {
        private val actorId = UUID.fromString("40000000-0000-0000-0000-000000000001")
        private val customerId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        private val storeId = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val courierId = UUID.fromString("30000000-0000-0000-0000-000000000001")
        private val digest = ByteArray(32) { 6 }

        @BeforeEach
        fun resetAndSeed() {
            removeAuditFailureTrigger()
            blindIndexes.reset(digest)
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    support_subject_search_rate_window,
                    operations_audit_record,
                    operations_operator_permission_grant,
                    identity_customer_support_profile_exact_index,
                    merchant_store_support_profile_exact_index,
                    delivery_external_courier_support_profile_exact_index,
                    identity_customer_support_profile,
                    merchant_store_support_profile,
                    delivery_external_courier_support_profile,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            seedProfiles()
        }

        @AfterEach
        fun cleanupTrigger() {
            removeAuditFailureTrigger()
        }

        @Test
        fun `authorized POST returns only masked bounded candidates after a PII-free Audit`() {
            grant()

            val result =
                mockMvc
                    .perform(
                        post(BASE)
                            .with(jwt().jwt { it.subject(actorId.toString()) })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(searchBody("Customer@Example.com")),
                    ).andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.items.length()").value(3))
                    .andExpect(jsonPath("$.items[0].subjectType").value("CUSTOMER"))
                    .andExpect(jsonPath("$.items[1].subjectType").value("STORE"))
                    .andExpect(jsonPath("$.items[2].subjectType").value("RIDER"))
                    .andExpect(jsonPath("$.items[0].maskedDisplayName").value("홍*동"))
                    .andExpect(jsonPath("$.items[0].maskedMatchedValue").value("h***@e***.com"))
                    .andExpect(jsonPath("$.matchedCount").value(3))
                    .andExpect(jsonPath("$.ambiguous").value(true))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andReturn()

            assertThat(result.response.contentAsString)
                .doesNotContain("Customer@Example.com", "customer@example.com", "vault:v7", hex(digest))
            assertThat(blindIndexes.invocations.get()).isOne()
            assertThat(blindIndexes.observedTransaction).isFalse()
            assertThat(count("support_subject_search_rate_window")).isOne()
            assertThat(count("operations_audit_record")).isOne()
            val audit =
                jdbcTemplate
                    .queryForMap(
                        """
                        SELECT audit_category, action, target_type, reason, before_summary, after_summary, source_reference
                          FROM operations_audit_record
                        """.trimIndent(),
                    ).values
                    .joinToString("|")
            assertThat(audit)
                .contains("PII_ACCESS", "SUPPORT_PII_ACCESS_RECORDED", "SUPPORT_SUBJECT_SEARCH", "CASE_INTAKE")
                .doesNotContain(
                    "Customer@Example.com",
                    "customer@example.com",
                    "h***@e***.com",
                    customerId.toString(),
                    storeId.toString(),
                    courierId.toString(),
                    hex(digest),
                    "vault:v7",
                )
        }

        @Test
        fun `permission and persistent rate guard stop the request before Vault`() {
            mockMvc
                .perform(
                    post(BASE)
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("first@example.com")),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            assertThat(blindIndexes.invocations.get()).isZero()
            assertThat(count("support_subject_search_rate_window")).isZero()

            grant()
            jdbcTemplate.update(
                """
                INSERT INTO support_subject_search_rate_window (actor_id, window_started_at, attempt_count, updated_at)
                VALUES (?, date_bin('5 minutes', now(), TIMESTAMPTZ '1970-01-01 00:00:00Z'), 30, now())
                """.trimIndent(),
                actorId,
            )
            mockMvc
                .perform(
                    post(BASE)
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("second@example.com")),
                ).andExpect(status().isTooManyRequests)
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("SUPPORT_SEARCH_RATE_LIMITED"))
            assertThat(blindIndexes.invocations.get()).isZero()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `concurrent rate attempts converge on exactly thirty accepted requests`() {
            grant()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val outcomes =
                    (1..31).map {
                        pool.submit<FailureCode?> {
                            start.await()
                            try {
                                preflight.authorizeAndConsume(actorId)
                                null
                            } catch (failure: DomainFailure) {
                                failure.code
                            }
                        }
                    }
                start.countDown()

                assertThat(outcomes.map { it.get() })
                    .containsExactlyInAnyOrderElementsOf(
                        List(30) { null } + FailureCode.SUPPORT_SEARCH_RATE_LIMITED,
                    )
            } finally {
                pool.shutdownNow()
            }

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM support_subject_search_rate_window WHERE actor_id = ?",
                    Int::class.java,
                    actorId,
                ),
            ).isEqualTo(30)
            assertThat(blindIndexes.invocations.get()).isZero()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `two service instances with skewed clocks cannot split one database rate window`() {
            grant()
            val instanceClocks =
                listOf(
                    Clock.fixed(Instant.parse("2026-08-11T00:04:59Z"), ZoneOffset.UTC),
                    Clock.fixed(Instant.parse("2026-08-11T00:05:01Z"), ZoneOffset.UTC),
                )
            val services =
                instanceClocks.map { instanceClock ->
                    SupportSubjectSearchApplicationService(
                        preflight,
                        blindIndexes,
                        searchTransaction,
                        identifiers,
                        instanceClock,
                    )
                }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val outcomes =
                    (1..31).map { attempt ->
                        pool.submit<FailureCode?> {
                            start.await()
                            try {
                                services[attempt % services.size].search(
                                    SearchSupportSubjectsCommand(
                                        actorId = actorId,
                                        criterionType = ExactSearchCriterionType.EMAIL,
                                        rawCriterion = "clock-skew@example.com",
                                        subjectTypes = listOf(SupportSearchSubjectType.CUSTOMER),
                                        reasonCode = SupportSearchReasonCode.CASE_INTAKE,
                                        correlationId = "clock-skew-$attempt",
                                    ),
                                )
                                null
                            } catch (failure: DomainFailure) {
                                failure.code
                            }
                        }
                    }
                start.countDown()

                assertThat(outcomes.map { it.get() })
                    .containsExactlyInAnyOrderElementsOf(
                        List(30) { null } + FailureCode.SUPPORT_SEARCH_RATE_LIMITED,
                    )
            } finally {
                pool.shutdownNow()
            }

            assertThat(count("support_subject_search_rate_window")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM support_subject_search_rate_window WHERE actor_id = ?",
                    Int::class.java,
                    actorId,
                ),
            ).isEqualTo(30)
            assertThat(blindIndexes.invocations.get()).isEqualTo(30)
            assertThat(count("operations_audit_record")).isEqualTo(30)
        }

        @Test
        fun `rate window retention cleanup is bounded concurrent and safely rerunnable`() {
            seedRateWindowsForRetention()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val firstPass =
                try {
                    val outcomes =
                        (1..2).map {
                            pool.submit<Int> {
                                start.await()
                                rateWindowRetention.purgeExpired(100).deletedCount
                            }
                        }
                    start.countDown()
                    outcomes.sumOf { it.get() }
                } finally {
                    pool.shutdownNow()
                }

            assertThat(firstPass).isEqualTo(200)
            val finalBatch = rateWindowRetention.purgeExpired(100)
            assertThat(finalBatch.deletedCount).isEqualTo(50)
            assertThat(finalBatch.remainingBacklog).isZero()
            assertThat(finalBatch.oldestRetainedWindowStartedAt).isNotNull()
            assertThat(finalBatch.oldestRetainedWindowStartedAt)
                .isAfter(finalBatch.observedAt.minusSeconds(24 * 60 * 60L))
            assertThat(rateWindowRetention.purgeExpired(100).deletedCount).isZero()
            assertThat(count("support_subject_search_rate_window")).isOne()
        }

        @Test
        fun `invalid raw input and unknown nested fields return generic 400 without persistence`() {
            grant()
            listOf(
                searchBody("private name@example.com"),
                searchBody("private@example.com").replace("\"value\":", "\"unexpected\":true,\"value\":"),
            ).forEach { body ->
                val result =
                    mockMvc
                        .perform(
                            post(BASE)
                                .with(jwt().jwt { it.subject(actorId.toString()) })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        ).andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                        .andReturn()
                assertThat(result.response.contentAsString).doesNotContain("private name", "private@example.com")
            }
            mockMvc
                .perform(
                    post("$BASE?value=private-in-url@example.com")
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("body@example.com")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(get(BASE).with(jwt().jwt { it.subject(actorId.toString()) }))
                .andExpect(status().isMethodNotAllowed)
            assertThat(blindIndexes.invocations.get()).isZero()
            assertThat(count("support_subject_search_rate_window")).isZero()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `permission revoked after HMAC is rechecked and no Audit or result is returned`() {
            grant()
            blindIndexes.afterGenerate = {
                jdbcTemplate.update(
                    "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now(), version = 2 WHERE actor_id = ?",
                    actorId,
                )
            }

            mockMvc
                .perform(
                    post(BASE)
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("revoked@example.com")),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            assertThat(blindIndexes.observedTransaction).isFalse()
            assertThat(count("support_subject_search_rate_window")).isOne()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `Vault and Audit failure return 503 with consumed rate but no partial result`() {
            grant()
            blindIndexes.failure = true
            var result = performSearch("vault-failure@example.com")
            assertThat(result.response.status).isEqualTo(503)
            assertThat(result.response.contentAsString)
                .contains("DEPENDENCY_UNAVAILABLE")
                .doesNotContain("vault-failure@example.com")
            assertThat(count("support_subject_search_rate_window")).isOne()
            assertThat(count("operations_audit_record")).isZero()

            jdbcTemplate.execute("TRUNCATE TABLE support_subject_search_rate_window")
            blindIndexes.failure = false
            installAuditFailureTrigger()
            result = performSearch("audit-failure@example.com")
            assertThat(result.response.status).isEqualTo(503)
            assertThat(result.response.contentAsString)
                .contains("DEPENDENCY_UNAVAILABLE")
                .doesNotContain("audit-failure@example.com", "h***@e***.com")
            assertThat(count("support_subject_search_rate_window")).isOne()
            assertThat(count("operations_audit_record")).isZero()
        }

        @Test
        fun `each owner query failure returns 503 instead of a partial or empty success`() {
            grant()
            mapOf(
                "CUSTOMER" to "identity_customer_support_profile",
                "STORE" to "merchant_store_support_profile",
                "RIDER" to "delivery_external_courier_support_profile",
            ).forEach { (subjectType, table) ->
                jdbcTemplate.execute("TRUNCATE TABLE support_subject_search_rate_window, operations_audit_record CASCADE")
                jdbcTemplate.execute("ALTER TABLE $table RENAME TO ${table}_unavailable")
                try {
                    val result =
                        mockMvc
                            .perform(
                                post(BASE)
                                    .with(jwt().jwt { it.subject(actorId.toString()) })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(searchBody("owner-failure@example.com", listOf(subjectType))),
                            ).andExpect(status().isServiceUnavailable)
                            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                            .andReturn()
                    assertThat(result.response.contentAsString)
                        .doesNotContain("owner-failure@example.com", "h***@e***.com")
                    assertThat(count("support_subject_search_rate_window")).isOne()
                    assertThat(count("operations_audit_record")).isZero()
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ${table}_unavailable RENAME TO $table")
                }
            }
        }

        @Test
        fun `genuine no-match returns an audited masked empty success`() {
            grant()
            blindIndexes.reset(ByteArray(32) { 8 })

            mockMvc
                .perform(
                    post(BASE)
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("none@example.com")),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.ambiguous").value(false))
                .andExpect(jsonPath("$.hasMore").value(false))
            assertThat(count("operations_audit_record")).isOne()
        }

        private fun performSearch(raw: String) =
            mockMvc
                .perform(
                    post(BASE)
                        .with(jwt().jwt { it.subject(actorId.toString()) })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody(raw)),
                ).andReturn()

        private fun searchBody(
            raw: String,
            subjectTypes: List<String> = listOf("CUSTOMER", "STORE", "RIDER"),
        ): String {
            val encodedSubjectTypes = subjectTypes.joinToString(",") { "\"$it\"" }
            return """
                {"criterion":{"type":"EMAIL","value":"$raw"},"subjectTypes":[$encodedSubjectTypes],"reasonCode":"CASE_INTAKE"}
                """.trimIndent()
        }

        private fun grant() {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'SUPPORT_SUBJECT_SEARCH', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                "support-search-test-grant:$actorId",
            )
        }

        private fun seedRateWindowsForRetention() {
            jdbcTemplate.execute(
                """
                INSERT INTO support_subject_search_rate_window (
                    actor_id, window_started_at, attempt_count, updated_at
                )
                SELECT md5('support-rate-retention-' || item)::uuid,
                       date_bin(
                           INTERVAL '5 minutes',
                           clock_timestamp() - INTERVAL '25 hours',
                           TIMESTAMPTZ '1970-01-01 00:00:00Z'
                       ),
                       1,
                       date_bin(
                           INTERVAL '5 minutes',
                           clock_timestamp() - INTERVAL '25 hours',
                           TIMESTAMPTZ '1970-01-01 00:00:00Z'
                       ) + INTERVAL '1 minute'
                  FROM generate_series(1, 250) AS item
                UNION ALL
                SELECT '50000000-0000-0000-0000-000000000001'::uuid,
                       date_bin(
                           INTERVAL '5 minutes',
                           clock_timestamp(),
                           TIMESTAMPTZ '1970-01-01 00:00:00Z'
                       ),
                       1,
                       clock_timestamp()
                """.trimIndent(),
            )
        }

        private fun seedProfiles() {
            jdbcTemplate.update(
                """
                INSERT INTO identity_customer_support_profile (
                    customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                    masked_display_name, primary_email_ciphertext, primary_email_key_version,
                    primary_email_aad_version, masked_primary_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:display', 7, 1, '홍*동', 'vault:v7:email', 7, 1,
                          'h***@e***.com', ?, ?)
                """.trimIndent(),
                customerId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_support_profile (
                    store_id, legal_display_name_ciphertext, legal_display_name_key_version,
                    legal_display_name_aad_version, masked_display_name,
                    support_email_ciphertext, support_email_key_version, support_email_aad_version,
                    masked_support_email, created_at, updated_at
                ) VALUES (?, 'vault:v7:legal', 7, 1, '빈*우', 'vault:v7:email', 7, 1,
                          's***@e***.com', ?, ?)
                """.trimIndent(),
                storeId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            jdbcTemplate.update(
                """
                INSERT INTO delivery_external_courier_support_profile (
                    external_courier_id, provider_code, provider_courier_reference_ciphertext,
                    provider_courier_reference_key_version, provider_courier_reference_aad_version,
                    display_name_ciphertext, display_name_key_version, display_name_aad_version, masked_display_name,
                    relay_email_ciphertext, relay_email_key_version, relay_email_aad_version, masked_relay_email,
                    created_at, updated_at
                ) VALUES (?, 'EXTERNAL_PROVIDER', 'vault:v7:reference', 7, 1,
                          'vault:v7:display', 7, 1, '라*더', 'vault:v7:email', 7, 1,
                          'r***@e***.com', ?, ?)
                """.trimIndent(),
                courierId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
            insertIndex("identity_customer_support_profile_exact_index", "customer_id", customerId)
            insertIndex("merchant_store_support_profile_exact_index", "store_id", storeId)
            insertIndex("delivery_external_courier_support_profile_exact_index", "external_courier_id", courierId)
        }

        private fun insertIndex(
            table: String,
            idColumn: String,
            id: UUID,
        ) {
            jdbcTemplate.update(
                "INSERT INTO $table ($idColumn, criterion_type, index_key_version, blind_index, created_at) " +
                    "VALUES (?, 'EMAIL', 3, ?, ?)",
                id,
                digest,
                Timestamp.from(NOW),
            )
        }

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION reject_support_search_test_audit()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'support search audit unavailable'; END
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER trg_reject_support_search_test_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION reject_support_search_test_audit()
                """.trimIndent(),
            )
        }

        private fun removeAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_reject_support_search_test_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_support_search_test_audit()")
        }

        private fun count(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun hex(bytes: ByteArray): String =
            java.util.HexFormat
                .of()
                .formatHex(bytes)

        @TestConfiguration(proxyBeanMethods = false)
        internal class SearchTestConfiguration {
            @Bean
            @Primary
            fun recordingBlindIndexPort(): RecordingBlindIndexPort = RecordingBlindIndexPort()
        }

        internal class RecordingBlindIndexPort : KeyedBlindIndexPort {
            val invocations = AtomicInteger()
            var observedTransaction = false
            var afterGenerate: () -> Unit = {}
            var failure = false
            private var digest = ByteArray(32)

            override fun generate(
                normalizedValue: NormalizedExactSearchValue,
                keyVersions: Set<Int>,
            ): List<BlindIndex> {
                invocations.incrementAndGet()
                observedTransaction = observedTransaction || TransactionSynchronizationManager.isActualTransactionActive()
                afterGenerate()
                if (failure) {
                    throw DomainFailure(
                        FailureCode.DEPENDENCY_UNAVAILABLE,
                        "Personal-data protection service is unavailable",
                    )
                }
                return keyVersions.sorted().map { BlindIndex(it, digest) }
            }

            override fun activeSearchKeyVersions(): Set<Int> = setOf(3)

            override fun writeKeyVersion(): Int = 3

            fun reset(digest: ByteArray) {
                this.digest = digest.copyOf()
                invocations.set(0)
                observedTransaction = false
                afterGenerate = {}
                failure = false
            }
        }

        private companion object {
            const val BASE = "/api/v1/support/searches"
            val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
        }
    }
