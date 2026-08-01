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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class OrdinaryPointAccrualPolicyControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val actorId = UUID.fromString("70000000-0000-0000-0000-000000000001")

        @BeforeEach
        fun resetMutableState() {
            jdbcTemplate.update("DELETE FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'")
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant")
            jdbcTemplate.update("DELETE FROM operations_audit_record")
            grant("POINT_ACCRUAL_POLICY_READ")
            grant("POINT_ACCRUAL_POLICY_WRITE")
        }

        @Test
        fun `seven operator paths enforce role headers and conditional policy bodies`() {
            val storeId = insertStore()
            val globalVersion = currentGlobalVersion()

            mockMvc
                .perform(
                    patch("$BASE/global")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-global-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "expectedPolicyVersionId": $globalVersion,
                              "state": "OVERRIDE",
                              "accrualRateBps": 300,
                              "roundingMode": "HALF_UP",
                              "issuerType": "PLATFORM",
                              "issuerReference": "platform:http",
                              "expiryRule": "SEOUL_CALENDAR_DAYS_FROM_COMPLETION",
                              "validityDays": 180,
                              "reason": "HTTP global policy proof"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("OVERRIDE"))
                .andExpect(jsonPath("$.accrualRateBps").value(300))

            val storePatch =
                mockMvc
                    .perform(
                        patch("$BASE/stores/$storeId")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "http-store-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {
                                  "state": "OVERRIDE",
                                  "accrualRateBps": 500,
                                  "roundingMode": "FLOOR",
                                  "issuerType": "STORE",
                                  "issuerReference": "store:$storeId",
                                  "expiryRule": "EXACT_DURATION_FROM_COMPLETION",
                                  "validityDays": 90,
                                  "reason": "HTTP Store policy proof"
                                }
                                """.trimIndent(),
                            ),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.scopeReference").value(storeId.toString()))
                    .andReturn()
            val storeVersion =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(storePatch.response.contentAsString)["policyVersionId"]
                    .longValue()

            mockMvc
                .perform(get("$BASE/global").with(operatorJwt()).header("X-Access-Reason", "Global review"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scopeType").value("GLOBAL"))
            mockMvc
                .perform(get("$BASE/global/versions?limit=1").with(operatorJwt()).header("X-Access-Reason", "History review"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").exists())
            mockMvc
                .perform(get("$BASE/stores?state=OVERRIDE").with(operatorJwt()).header("X-Access-Reason", "Head list"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].scopeReference").value(storeId.toString()))
            mockMvc
                .perform(get("$BASE/stores/$storeId").with(operatorJwt()).header("X-Access-Reason", "Store current"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.selectionSource").value("STORE_OVERRIDE"))
            mockMvc
                .perform(get("$BASE/stores/$storeId/versions").with(operatorJwt()).header("X-Access-Reason", "Store history"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].policyVersionId").value(storeVersion))

            mockMvc
                .perform(
                    patch("$BASE/stores/$storeId")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "http-inherit-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "expectedPolicyVersionId": $storeVersion,
                              "state": "INHERIT_GLOBAL",
                              "reason": "Return Store to global"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("INHERIT_GLOBAL"))
                .andExpect(jsonPath("$.accrualRateBps").doesNotExist())
        }

        @Test
        fun `role grant reason idempotency and conditional validation map to the documented statuses`() {
            mockMvc
                .perform(get("$BASE/global").header("X-Access-Reason", "No token"))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(get("$BASE/global").with(operatorJwt()).header("X-Access-Reason", ""))
                .andExpect(status().isBadRequest)
            jdbcTemplate.update("DELETE FROM operations_operator_permission_grant WHERE permission = 'POINT_ACCRUAL_POLICY_READ'")
            mockMvc
                .perform(get("$BASE/global").with(operatorJwt()).header("X-Access-Reason", "No grant"))
                .andExpect(status().isForbidden)

            val storeId = insertStore()
            mockMvc
                .perform(
                    patch("$BASE/stores/$storeId")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "invalid-shape-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"state":"INHERIT_GLOBAL","accrualRateBps":100,"reason":"Invalid shape"}"""),
                ).andExpect(status().isBadRequest)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_point_accrual_policy_head WHERE scope_type = 'STORE'",
                    Long::class.java,
                ),
            ).isZero()
        }

        private fun operatorJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun grant(permission: String) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                permission,
                "http-grant:$permission:${UUID.randomUUID()}",
            )
        }

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", it)
            }

        private fun currentGlobalVersion(): Long =
            jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM operations_point_accrual_policy_head WHERE scope_type = 'GLOBAL'",
                Long::class.java,
            )!!

        private companion object {
            const val BASE = "/api/v1/operations/policies/ordinary-point-accrual"
        }
    }
