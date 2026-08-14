package io.github.kdh949.beanflow.operations.internal

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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The operator brand endpoints over HTTP.
 *
 * The service-level tests already pin what a brand command does to the data. What this test pins
 * is the boundary around it: the role, the explicit grant, the reason, the idempotency key, and
 * the fact that a refused command leaves no brand and no audit trail behind.
 */
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OperatorBrandControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val actorId = UUID.fromString("70000000-0000-0000-0000-000000000001")
        private val jsonMapper = JsonMapper.builder().build()

        @BeforeEach
        fun resetBrandsAndGrants() {
            jdbcTemplate.update("DELETE FROM merchant_brand_command")
            jdbcTemplate.update("DELETE FROM discovery_store_search_term")
            jdbcTemplate.update("UPDATE merchant_store SET brand_id = NULL")
            jdbcTemplate.update("DELETE FROM merchant_brand")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            grant("STORE_BRAND_MANAGE")
        }

        @Test
        fun `the six brand paths create, read, update and assign a brand`() {
            val created =
                mockMvc
                    .perform(
                        post("$BASE/brands")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-brand-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"name":"스타벅스","reason":"HTTP brand proof"}"""),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.name").value("스타벅스"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.assignedStoreCount").value(0))
                    .andReturn()
            val brandId = readField(created.response.contentAsString, "brandId")

            mockMvc
                .perform(get("$BASE/brands/$brandId").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("스타벅스"))

            mockMvc
                .perform(get("$BASE/brands").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())

            val storeId = insertStore()
            mockMvc
                .perform(
                    put("$BASE/stores/$storeId/brand")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-assign-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"brandId":"$brandId","reason":"HTTP assign proof"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.brandName").value("스타벅스"))

            mockMvc
                .perform(
                    patch("$BASE/brands/$brandId")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-rename-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스코리아","reason":"HTTP rename proof"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("스타벅스코리아"))
                .andExpect(jsonPath("$.assignedStoreCount").value(1))
            assertThat(brandTerms(UUID.fromString(storeId))).containsExactly("스타벅스코리아")

            mockMvc
                .perform(
                    delete("$BASE/stores/$storeId/brand")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-clear-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"HTTP clear proof"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.brandId").doesNotExist())
            assertThat(brandTerms(UUID.fromString(storeId))).isEmpty()

            // 명령 넷은 각각 AuditRecord를 남기고 조회 셋은 남기지 않는다.
            assertThat(auditActions()).containsExactlyInAnyOrder(
                "BRAND_CREATED",
                "BRAND_UPDATED",
                "STORE_BRAND_ASSIGNED",
                "STORE_BRAND_CLEARED",
            )
        }

        @Test
        fun `a replayed create returns the first brand and appends no second audit record`() {
            val body = """{"name":"스타벅스","reason":"Replay proof"}"""
            val first =
                mockMvc
                    .perform(
                        post("$BASE/brands")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-replay-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val replay =
                mockMvc
                    .perform(
                        post("$BASE/brands")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-replay-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andExpect(status().isCreated)
                    .andReturn()

            assertThat(readField(replay.response.contentAsString, "brandId"))
                .isEqualTo(readField(first.response.contentAsString, "brandId"))
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_brand", Long::class.java)).isEqualTo(1)
            // 재실행은 아무것도 바꾸지 않았으므로 두 번째 감사 기록을 남기면 없던 변경을 주장하게 된다.
            assertThat(auditActions()).containsExactly("BRAND_CREATED")

            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-replay-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"블루보틀","reason":"Replay proof"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        }

        @Test
        fun `a duplicate active name is a conflict that leaves the first brand alone`() {
            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-dup-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":"First"}"""),
                ).andExpect(status().isCreated)

            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-dup-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"  스타벅스  ","reason":"Duplicate"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("BRAND_NAME_ALREADY_IN_USE"))

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_brand", Long::class.java)).isEqualTo(1)
        }

        @Test
        fun `the role, the grant, the reason and the idempotency key are each required`() {
            mockMvc
                .perform(
                    post("$BASE/brands")
                        .header("Idempotency-Key", "http-auth-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":"No token"}"""),
                ).andExpect(status().isUnauthorized)

            // 매장주 역할로는 브랜드를 만들 수 없다(ADR-112 4절).
            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(storeOwnerJwt())
                        .header("Idempotency-Key", "http-auth-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":"Store owner"}"""),
                ).andExpect(status().isForbidden)

            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":"No key"}"""),
                ).andExpect(status().isBadRequest)

            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-auth-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":""}"""),
                ).andExpect(status().isBadRequest)

            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            mockMvc
                .perform(
                    post("$BASE/brands")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-auth-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"스타벅스","reason":"No grant"}"""),
                ).andExpect(status().isForbidden)
            mockMvc.perform(get("$BASE/brands").with(operatorJwt())).andExpect(status().isForbidden)

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_brand", Long::class.java)).isZero()
            assertThat(auditActions()).isEmpty()
        }

        @Test
        fun `an unknown brand is not found and an empty update is rejected`() {
            val missing = UUID.randomUUID()
            mockMvc
                .perform(get("$BASE/brands/$missing").with(operatorJwt()))
                .andExpect(status().isNotFound)

            val created =
                mockMvc
                    .perform(
                        post("$BASE/brands")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-empty-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"name":"스타벅스","reason":"Created"}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val brandId = readField(created.response.contentAsString, "brandId")

            mockMvc
                .perform(
                    patch("$BASE/brands/$brandId")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-empty-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"Changes nothing"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `the brand list pages with a signed cursor bound to this endpoint`() {
            listOf("블루보틀", "스타벅스", "이디야").forEachIndexed { index, name ->
                mockMvc
                    .perform(
                        post("$BASE/brands")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-page-000$index")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"name":"$name","reason":"Page $index"}"""),
                    ).andExpect(status().isCreated)
            }

            val firstPage =
                mockMvc
                    .perform(get("$BASE/brands").with(operatorJwt()).param("limit", "2"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andReturn()
            val cursor = readField(firstPage.response.contentAsString, "page", "nextCursor")

            mockMvc
                .perform(get("$BASE/brands").with(operatorJwt()).param("cursor", cursor).param("limit", "2"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())

            mockMvc
                .perform(get("$BASE/brands").with(operatorJwt()).param("cursor", "not-a-signed-cursor"))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(get("$BASE/brands").with(operatorJwt()).param("limit", "51"))
                .andExpect(status().isBadRequest)
        }

        private fun readField(
            json: String,
            vararg path: String,
        ): String {
            var node = jsonMapper.readTree(json)
            path.forEach { node = node.get(it) }
            return node.stringValue()
        }

        private fun brandTerms(storeId: UUID): List<String> =
            jdbcTemplate.query(
                "SELECT term_normalized FROM discovery_store_search_term WHERE store_id = ? AND term_kind = 'BRAND_NAME'",
                { row, _ -> row.getString("term_normalized") },
                storeId,
            )

        private fun auditActions(): List<String> =
            jdbcTemplate.query(
                "SELECT action FROM operations_audit_record",
                { row, _ -> row.getString("action") },
            )

        private fun operatorJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun storeOwnerJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_OWNER")) }
                .authorities(SimpleGrantedAuthority("ROLE_STORE_OWNER"))

        private fun grant(permission: String) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                permission,
                "http-brand-grant:$permission:${UUID.randomUUID()}",
            )
        }

        private fun insertStore(): String =
            UUID.randomUUID().toString().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (CAST(? AS uuid), true, true)",
                    it,
                )
            }

        private companion object {
            const val BASE = "/api/v1/operations"
        }
    }
