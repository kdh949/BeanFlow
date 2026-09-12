package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("checks committed point account resolution and the search to adjustment workflow")
@SpringBootTest
internal class OperationsCustomerPointAccountIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mapper: ObjectMapper,
) {
    private val actor = UUID.randomUUID()

    @BeforeEach fun clean() {
        dropFailure()
        jdbc.execute(
            "TRUNCATE identity_customer_account, loyalty_point_account, operations_operator_permission_grant, operations_audit_record CASCADE",
        )
    }

    @AfterEach fun dropFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS point_resolve_audit_failure ON operations_audit_record")
        jdbc.execute("DROP FUNCTION IF EXISTS test_point_resolve_audit_failure()")
    }

    @Test fun `search selection resolves the correct account and reuses existing read and idempotent adjustment APIs`() {
        listOf("CUSTOMER_ACCOUNT_SEARCH", "POINT_ACCOUNT_READ", "POINT_ADJUSTMENT").forEach(::grant)
        val customerId = customer("minsu01", "김민수")
        val accountId = account(customerId)
        val otherCustomer = customer("other01", "다른 고객")
        val otherAccount = account(otherCustomer)
        val found =
            mvc
                .perform(search("minsu01"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val selectedId =
            UUID.fromString(
                mapper
                    .readTree(found)
                    .path("items")
                    .get(0)
                    .path("customerId")
                    .asString(),
            )
        assertThat(selectedId).isEqualTo(customerId)
        val resolved =
            mvc
                .perform(resolve(selectedId))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andReturn()
                .response.contentAsString
        val selectedAccount = UUID.fromString(mapper.readTree(resolved).path("accountId").asString())
        mvc.perform(read(selectedAccount)).andExpect(status().isOk).andExpect(jsonPath("$.availablePointsKrw").value(0))
        val key = UUID.randomUUID().toString()
        repeat(2) {
            mvc
                .perform(adjust(selectedAccount, key))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.account.availablePointsKrw").value(125))
        }
        mvc.perform(read(selectedAccount)).andExpect(status().isOk).andExpect(jsonPath("$.availablePointsKrw").value(125))
        mvc.perform(read(otherAccount)).andExpect(status().isOk).andExpect(jsonPath("$.availablePointsKrw").value(0))
        mvc
            .perform(read(selectedAccount, "/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].amountKrw").value(125))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_adjustment_command_idempotency", Long::class.java)).isOne()
        val audit = jdbc.queryForMap("SELECT * FROM operations_audit_record WHERE action = 'POINT_ACCOUNT_RESOLVED'")
        assertThat(audit["audit_category"]).isEqualTo("PII_ACCESS")
        assertThat(audit["target_id"]).isEqualTo(accountId)
        assertThat(audit["after_summary"].toString()).contains(customerId.toString()).doesNotContain("minsu01", "김민수")
    }

    @Test fun `search or adjustment grant cannot replace read grant and revoked grants are denied`() {
        val customerId = customer("minsu01", "김민수")
        account(customerId)
        listOf("CUSTOMER_ACCOUNT_SEARCH", "POINT_ADJUSTMENT").forEach(::grant)
        mvc.perform(resolve(customerId)).andExpect(status().isForbidden)
        grant("POINT_ACCOUNT_READ")
        mvc.perform(resolve(customerId)).andExpect(status().isOk)
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE permission = 'POINT_ACCOUNT_READ'",
        )
        mvc.perform(resolve(customerId)).andExpect(status().isForbidden)
    }

    @Test fun `read permission does not authorize adjustment and each request checks current grant`() {
        grant("POINT_ACCOUNT_READ")
        val customerId = customer("minsu01", "김민수")
        val accountId = account(customerId)
        mvc.perform(resolve(customerId)).andExpect(status().isOk)
        mvc.perform(adjust(accountId, UUID.randomUUID().toString())).andExpect(status().isForbidden)
        grant("POINT_ADJUSTMENT")
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE permission = 'POINT_ADJUSTMENT'",
        )
        mvc.perform(adjust(accountId, UUID.randomUUID().toString())).andExpect(status().isForbidden)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_transaction", Long::class.java)).isZero()
    }

    @Test fun `missing account remains an integrity failure without lazy account creation`() {
        grant("POINT_ACCOUNT_READ")
        listOf(customer("minsu01", "김민수"), UUID.randomUUID()).forEach {
            mvc
                .perform(resolve(it))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("POINT_ACCOUNT_INTEGRITY_FAILURE"))
                .andExpect(jsonPath("$.accountId").doesNotExist())
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isZero()
    }

    @Test fun `role authentication and fixed access reason are required`() {
        grant("POINT_ACCOUNT_READ")
        val customerId = customer("minsu01", "김민수")
        account(customerId)
        val path = "/api/v1/operations/customers/$customerId/point-account"
        mvc.perform(get(path).header("X-Access-Reason", "POINT_ACCOUNT_INVESTIGATION")).andExpect(status().isUnauthorized)
        mvc.perform(resolve(customerId, role = "CUSTOMER")).andExpect(status().isForbidden)
        mvc.perform(get(path).with(operator())).andExpect(status().isBadRequest)
        mvc.perform(resolve(customerId, reason = "OTHER")).andExpect(status().isBadRequest)
    }

    @Test fun `immediate and deferred audit failure never return a customer account mapping`() {
        grant("POINT_ACCOUNT_READ")
        val customerId = customer("minsu01", "김민수")
        account(customerId)
        listOf(false, true).forEach { deferred ->
            dropFailure()
            jdbc.execute(
                "CREATE FUNCTION test_point_resolve_audit_failure() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION USING ERRCODE = ''23514'', MESSAGE = ''injected audit failure''; END'",
            )
            val timing =
                if (deferred) {
                    "CONSTRAINT TRIGGER point_resolve_audit_failure AFTER INSERT ON operations_audit_record DEFERRABLE INITIALLY DEFERRED"
                } else {
                    "TRIGGER point_resolve_audit_failure BEFORE INSERT ON operations_audit_record"
                }
            jdbc.execute("CREATE $timing FOR EACH ROW EXECUTE FUNCTION test_point_resolve_audit_failure()")
            mvc
                .perform(resolve(customerId))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.accountId").doesNotExist())
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
        }
    }

    private fun operator(role: String = "PLATFORM_OPERATOR") =
        jwt().jwt { it.subject(actor.toString()).claim("roles", listOf(role)) }.authorities(SimpleGrantedAuthority("ROLE_$role"))

    private fun resolve(
        customerId: UUID,
        reason: String = "POINT_ACCOUNT_INVESTIGATION",
        role: String = "PLATFORM_OPERATOR",
    ) = get("/api/v1/operations/customers/$customerId/point-account").with(operator(role)).header("X-Access-Reason", reason)

    private fun read(
        accountId: UUID,
        suffix: String = "",
    ) = get("/api/v1/operations/point-accounts/$accountId$suffix").with(operator()).header("X-Access-Reason", "POINT_ACCOUNT_INVESTIGATION")

    private fun adjust(
        accountId: UUID,
        key: String,
    ) = post("/api/v1/operations/point-accounts/$accountId/adjustments")
        .with(operator())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            mapper.writeValueAsString(
                mapOf(
                    "amountKrw" to 125,
                    "reason" to "Verified customer point correction",
                    "evidenceReferences" to listOf("test:customer-point-review"),
                    "issuer" to mapOf("issuerType" to "PLATFORM", "issuerReference" to "platform:test"),
                    "expiresAt" to "2099-01-01T00:00:00Z",
                ),
            ),
        )

    private fun account(customerId: UUID): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO loyalty_point_account(id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw, version) VALUES (?, ?, 0, 0, 0, 0)",
                it,
                customerId,
            )
        }

    private fun search(
        loginId: String,
        role: String = "PLATFORM_OPERATOR",
    ) = post(PATH)
        .with(jwt().jwt { it.subject(actor.toString()).claim("roles", listOf(role)) }.authorities(SimpleGrantedAuthority("ROLE_$role")))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(loginId))

    private fun body(loginId: String) =
        mapper.writeValueAsString(mapOf("loginId" to loginId, "reasonCode" to "POINT_ACCOUNT_INVESTIGATION"))

    private fun customer(
        loginId: String,
        name: String,
    ): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO identity_customer_account(id, login_id, password_hash, credential_version, display_name, state, created_at, updated_at, version) VALUES (?, ?, ?, 0, ?, 'ACTIVE', now(), now(), 0)",
                it,
                loginId,
                "unused-authentication-fixture",
                name,
            )
        }

    private fun grant(permission: String) {
        jdbc.update(
            "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', now(), 1, ?)",
            actor,
            permission,
            "customer-search-test:$permission:$actor",
        )
    }

    private companion object {
        const val PATH = "/api/v1/operations/customer-searches"
    }
}
