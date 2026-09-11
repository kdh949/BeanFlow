package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed cost owner registration, races and audit rollback")
@SpringBootTest
internal class PointCostIssuerIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mapper: ObjectMapper,
) {
    private val actor = UUID.fromString("81000000-0000-4000-8000-000000000001")
    private val other = UUID.fromString("81000000-0000-4000-8000-000000000002")
    private lateinit var fixture: OrderCreationFixture

    @BeforeEach fun seed() {
        OrderCreationDatabaseFixture.clean(jdbc)
        jdbc.execute(
            "TRUNCATE operations_platform_point_cost_owner, merchant_brand, operations_operator_permission_grant, operations_audit_record CASCADE",
        )
        fixture = OrderCreationFixture()
        OrderCreationDatabaseFixture.insertBase(jdbc, fixture)
        grant(actor, "POINT_ADJUSTMENT")
        grant(actor, "POINT_ACCRUAL_POLICY_WRITE")
        grant(other, "POINT_ACCRUAL_POLICY_WRITE")
    }

    @Test fun `selection maps current merchant names to actual owner references without identity grants`() {
        val brand = UUID.randomUUID()
        insertBrand(brand, "현재 비용 브랜드", "ACTIVE")
        list(
            "STORE",
        ).andExpect(
            status().isOk,
        ).andExpect(
            jsonPath("$.items[0].issuerReference").value(fixture.storeId.toString()),
        ).andExpect(jsonPath("$.items[0].displayName").value("BeanFlow Test Store"))
        list(
            "BRAND",
        ).andExpect(
            status().isOk,
        ).andExpect(
            jsonPath("$.items[0].issuerReference").value(brand.toString()),
        ).andExpect(jsonPath("$.items[0].displayName").value("현재 비용 브랜드"))
        jdbc.update("UPDATE merchant_store_discovery_profile SET name = '변경된 실제 매장명' WHERE store_id = ?", fixture.storeId)
        list("STORE", query = "변경된").andExpect(status().isOk).andExpect(jsonPath("$.items[0].displayName").value("변경된 실제 매장명"))
        list("BRAND", query = "없는 브랜드").andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
    }

    @Test fun `platform registration preserves explicit policy source and replays one committed identity`() {
        val beforeVersions = count("operations_point_accrual_policy_version")
        val beforeLots = count("loyalty_point_lot")
        val results =
            Executors.newFixedThreadPool(2).use { pool ->
                pool
                    .invokeAll(
                        List(2) {
                            Callable {
                                register(
                                    actor,
                                    "register-platform-same",
                                    "고객 서비스 비용",
                                ).andExpect(status().isCreated).andReturn().response.contentAsString
                            }
                        },
                    ).map { it.get() }
            }
        assertThat(results[0]).isEqualTo(results[1])
        val issuer = mapper.readTree(results[0])["issuerReference"].asText()
        assertThat(issuer).startsWith("platform:")
        assertThat(count("operations_platform_point_cost_owner")).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_audit_record WHERE action = 'PLATFORM_POINT_COST_OWNER_REGISTERED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        register(actor, "register-platform-same", "다른 비용").andExpect(status().isConflict)
        val page =
            mapper.readTree(
                list("PLATFORM")
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val items = page["items"]
        assertThat((0 until items.size()).map { items[it]["source"].asText() }).contains("GLOBAL_POLICY", "REGISTERED_PLATFORM")
        assertThat((0 until items.size()).map { items[it]["issuerReference"].asText() }).contains(issuer)
        assertThat(count("operations_point_accrual_policy_version")).isEqualTo(beforeVersions)
        assertThat(count("loyalty_point_lot")).isEqualTo(beforeLots)
    }

    @Test fun `different actors cannot register the same normalized platform name twice`() {
        val statuses =
            Executors.newFixedThreadPool(2).use { pool ->
                pool
                    .invokeAll(
                        listOf(
                            Callable {
                                register(actor, "register-name-first", "Cafe").andReturn().response.status
                            },
                            Callable { register(other, "register-name-second", "Ｃａｆｅ").andReturn().response.status },
                        ),
                    ).map { it.get() }
            }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409)
        assertThat(count("operations_platform_point_cost_owner")).isEqualTo(1)
    }

    @Test fun `cursor binds purpose actor type and search while archived brand pages keep continuation`() {
        insertBrand(UUID.randomUUID(), "a archived", "ARCHIVED")
        insertBrand(UUID.randomUUID(), "b active", "ACTIVE")
        val first =
            mapper.readTree(
                list("BRAND", limit = 1)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items").isEmpty)
                    .andReturn()
                    .response.contentAsString,
            )
        val cursor = first["nextCursor"].asText()
        list("BRAND", cursor = cursor, limit = 1).andExpect(status().isOk).andExpect(jsonPath("$.items[0].displayName").value("b active"))
        list("BRAND", purpose = "POLICY", cursor = cursor).andExpect(status().isBadRequest)
        list("BRAND", query = "b", cursor = cursor).andExpect(status().isBadRequest)
        list("STORE", cursor = cursor).andExpect(status().isBadRequest)
        grant(other, "POINT_ADJUSTMENT")
        list("BRAND", cursor = cursor, actorId = other).andExpect(status().isBadRequest)
    }

    @Test fun `registration audit failure rolls back and revoked permissions deny later discovery and writes`() {
        jdbc.execute(
            "ALTER TABLE operations_audit_record ADD CONSTRAINT test_platform_cost_audit_failure CHECK (action <> 'PLATFORM_POINT_COST_OWNER_REGISTERED')",
        )
        try {
            register(actor, "register-audit-failure", "감사 검증 비용").andExpect(status().isServiceUnavailable)
            assertThat(count("operations_platform_point_cost_owner")).isZero()
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_platform_cost_audit_failure")
        }
        jdbc.update("UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?", actor)
        list("STORE").andExpect(status().isForbidden)
        list("PLATFORM", purpose = "POLICY").andExpect(status().isForbidden)
        register(actor, "register-revoked-key", "권한 없는 비용").andExpect(status().isForbidden)
    }

    @Test fun `adjustment permission alone cannot register or browse policy candidates`() {
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'POINT_ACCRUAL_POLICY_WRITE'",
            actor,
        )
        list("PLATFORM").andExpect(status().isOk).andExpect(jsonPath("$.canRegisterPlatform").value(false))
        list("PLATFORM", purpose = "POLICY").andExpect(status().isForbidden)
        register(actor, "register-adjustment-only", "권한 분리 비용").andExpect(status().isForbidden)
    }

    private fun list(
        type: String,
        purpose: String = "ADJUSTMENT",
        query: String? = null,
        cursor: String? = null,
        limit: Int = 20,
        actorId: UUID = actor,
    ) = mvc.perform(
        get(
            "/api/v1/operations/point-cost-issuers",
        ).with(auth(actorId))
            .param("type", type)
            .param("purpose", purpose)
            .param("limit", limit.toString())
            .also { builder ->
                query?.let { builder.param("query", it) }
                cursor?.let { builder.param("cursor", it) }
            },
    )

    private fun register(
        actorId: UUID,
        key: String,
        name: String,
    ) = mvc.perform(
        post(
            "/api/v1/operations/platform-point-cost-owners",
        ).with(auth(actorId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(RegisterPlatformPointCostOwnerRequest(name, "확인된 비용 책임 등록"))),
    )

    private fun auth(id: UUID) = jwt().jwt { it.subject(id.toString()) }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun grant(
        id: UUID,
        permission: String,
    ) {
        jdbc.update(
            "INSERT INTO operations_operator_permission_grant (actor_id, permission, state, granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', now(), 1, ?)",
            id,
            permission,
            "cost-issuer:$id:$permission",
        )
    }

    private fun insertBrand(
        id: UUID,
        name: String,
        state: String,
    ) {
        jdbc.update(
            "INSERT INTO merchant_brand (id, name, normalized_name, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, now(), now(), 0)",
            id,
            name,
            name.lowercase(),
            state,
        )
    }

    private fun count(table: String) = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))
}
