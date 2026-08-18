package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.search-index-coverage.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.search-index-rebuild-command-retention.initial-delay-ms=3600000",
    ],
)
internal class OperatorSearchIndexRebuildControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val actorId = UUID.fromString("70000000-0000-0000-0000-000000000010")

        @BeforeEach
        fun resetData() {
            jdbcTemplate.update("DELETE FROM operations_search_index_rebuild_command")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM discovery_store_search_term")
            jdbcTemplate.update("DELETE FROM merchant_menu")
            jdbcTemplate.update("DELETE FROM merchant_store_discovery_profile")
            jdbcTemplate.update("DELETE FROM merchant_store")
            grant()
        }

        @Test
        fun `operator rebuild indexes every current store and replays the stored result`() {
            val firstStore = insertStore("스타벅스 강남점")
            val secondStore = insertStore("블루보틀 삼청점")
            val request = """{"reason":"Direct store data refresh"}"""

            val first =
                mockMvc
                    .perform(
                        post(PATH)
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-rebuild-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"reason":"  Direct store data refresh  "}"""),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.complete").value(true))
                    .andExpect(jsonPath("$.indexedStoreCount").value(2))
                    .andExpect(jsonPath("$.skippedStoreCount").value(0))
                    .andExpect(jsonPath("$.failedStoreIds.length()").value(0))
                    .andReturn()

            assertThat(storeNameTerms()).containsExactlyInAnyOrder(firstStore, secondStore)
            assertThat(auditActions()).containsExactly("STORE_SEARCH_INDEX_REBUILD_REQUESTED")

            jdbcTemplate.update(
                "UPDATE merchant_store_discovery_profile SET name = ? WHERE store_id = ?",
                "스타벅스 역삼점",
                firstStore,
            )

            val replay =
                mockMvc
                    .perform(
                        post(PATH)
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-rebuild-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request),
                    ).andExpect(status().isOk)
                    .andReturn()

            assertThat(replay.response.contentAsString).isEqualTo(first.response.contentAsString)
            assertThat(currentStoreNameTerm(firstStore)).isEqualTo("스타벅스 강남점")
            assertThat(auditActions()).containsExactly("STORE_SEARCH_INDEX_REBUILD_REQUESTED")

            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Different reason"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        }

        @Test
        fun `partial rebuild returns failed store IDs and continues with healthy stores`() {
            val healthy = insertStore("정상 매장")
            val broken = insertStoreWithoutProfile()

            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Partial failure proof"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.indexedStoreCount").value(1))
                .andExpect(jsonPath("$.failedStoreIds[0]").value(broken.toString()))

            assertThat(storeNameTerms()).containsExactly(healthy)
            assertThat(auditActions()).containsExactly("STORE_SEARCH_INDEX_REBUILD_REQUESTED")
        }

        @Test
        fun `role grant request shape and running command state are all enforced`() {
            val body = """{"reason":"Authorization proof"}"""

            mockMvc
                .perform(post(PATH).header("Idempotency-Key", "http-rebuild-0003").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(
                    post(PATH)
                        .with(storeOwnerJwt())
                        .header("Idempotency-Key", "http-rebuild-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(post(PATH).with(operatorJwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"   "}"""),
                ).andExpect(status().isBadRequest)

            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)

            grant()
            insertRunningCommand("http-rebuild-0003", "Authorization proof")
            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isConflict)
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"))
        }

        @Test
        fun `a failed command keeps its key bound to the payload and retries on the same row`() {
            insertStore("재시도 매장")
            val commandId = insertCommand("http-rebuild-0004", "Retry proof", "FAILED_RETRYABLE", attemptCount = 1)

            // The row survives the failure, so the key is still bound to the reason it was accepted
            // with. Deleting it on failure was what let a different reason through as a new command.
            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Another reason entirely"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Retry proof"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.complete").value(true))

            assertThat(commandState(commandId)).isEqualTo("COMPLETED")
            assertThat(attemptCount(commandId)).isEqualTo(2)
            // The audit reference is per attempt, so the retry is recorded rather than deduplicated
            // away by the reference the first attempt would have used.
            assertThat(auditSourceReferences()).containsExactly("search-index-rebuild:$commandId:2")
        }

        @Test
        fun `unknown and manual review commands are never rerun automatically`() {
            insertStore("보류 매장")
            insertCommand("http-rebuild-0005", "Unknown outcome", "UNKNOWN", attemptCount = 1)
            insertCommand("http-rebuild-0006", "Exhausted", "MANUAL_REVIEW", attemptCount = 5)

            listOf("http-rebuild-0005" to "Unknown outcome", "http-rebuild-0006" to "Exhausted").forEach { (key, reason) ->
                mockMvc
                    .perform(
                        post(PATH)
                            .with(operatorJwt())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"reason":"$reason"}"""),
                    ).andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_MANUAL_REVIEW_REQUIRED"))
                    // Retry-After would tell an operator to retry work that must not be repeated.
                    .andExpect(header().doesNotExist("Retry-After"))
            }

            assertThat(storeNameTerms()).isEmpty()
            assertThat(auditSourceReferences()).isEmpty()
        }

        @Test
        fun `retry attempts are capped and the command escalates to manual review`() {
            insertStore("상한 매장")
            val commandId = insertCommand("http-rebuild-0007", "Capped", "FAILED_RETRYABLE", attemptCount = 5)

            mockMvc
                .perform(
                    post(PATH)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rebuild-0007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Capped"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_MANUAL_REVIEW_REQUIRED"))

            assertThat(commandState(commandId)).isEqualTo("MANUAL_REVIEW")
            assertThat(attemptCount(commandId)).isEqualTo(5)
            assertThat(storeNameTerms()).isEmpty()
        }

        private fun insertStore(name: String): UUID =
            UUID.randomUUID().also { storeId ->
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                    storeId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
                    VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0361, 37.5006), 4326)::geography, '1168010100')
                    """.trimIndent(),
                    storeId,
                    name,
                )
            }

        private fun insertStoreWithoutProfile(): UUID =
            UUID.randomUUID().also { storeId ->
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                    storeId,
                )
            }

        private fun insertRunningCommand(
            idempotencyKey: String,
            reason: String,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_search_index_rebuild_command (
                    id, actor_id, idempotency_key, payload_hash, state, created_at, retention_expires_at
                ) VALUES (?, ?, ?, ?, 'RUNNING', now(), now() + interval '90 days')
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                idempotencyKey,
                sha256("SEARCH_INDEX_REBUILD\u001F$reason"),
            )
        }

        private fun insertCommand(
            idempotencyKey: String,
            reason: String,
            state: String,
            attemptCount: Int,
        ): UUID =
            UUID.randomUUID().also { commandId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO operations_search_index_rebuild_command (
                        id, actor_id, idempotency_key, payload_hash, state, attempt_count,
                        created_at, last_failure_at, retention_expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, now(), now(), now() + interval '90 days')
                    """.trimIndent(),
                    commandId,
                    actorId,
                    idempotencyKey,
                    sha256("SEARCH_INDEX_REBUILD\u001F$reason"),
                    state,
                    attemptCount,
                )
            }

        private fun commandState(commandId: UUID): String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM operations_search_index_rebuild_command WHERE id = ?",
                    String::class.java,
                    commandId,
                ),
            )

        private fun attemptCount(commandId: UUID): Int =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM operations_search_index_rebuild_command WHERE id = ?",
                    Int::class.java,
                    commandId,
                ),
            )

        private fun auditSourceReferences(): List<String> =
            jdbcTemplate.query(
                "SELECT source_reference FROM operations_audit_record ORDER BY source_reference",
                { row, _ -> row.getString("source_reference") },
            )

        private fun storeNameTerms(): List<UUID> =
            jdbcTemplate.query(
                "SELECT store_id FROM discovery_store_search_term WHERE term_kind = 'STORE_NAME' ORDER BY store_id",
                { row, _ -> row.getObject("store_id", UUID::class.java) },
            )

        private fun currentStoreNameTerm(storeId: UUID): String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT display_text FROM discovery_store_search_term WHERE store_id = ? AND term_kind = 'STORE_NAME'",
                    String::class.java,
                    storeId,
                ),
            )

        private fun auditActions(): List<String> =
            jdbcTemplate.query("SELECT action FROM operations_audit_record ORDER BY action", { row, _ -> row.getString("action") })

        private fun grant() {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, 'STORE_BRAND_MANAGE', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                "http-rebuild-grant:${UUID.randomUUID()}",
            )
        }

        private fun operatorJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun storeOwnerJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_OWNER")) }
                .authorities(SimpleGrantedAuthority("ROLE_STORE_OWNER"))

        private fun sha256(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

        private companion object {
            const val PATH = "/api/v1/operations/search-index/rebuild"
        }
    }
