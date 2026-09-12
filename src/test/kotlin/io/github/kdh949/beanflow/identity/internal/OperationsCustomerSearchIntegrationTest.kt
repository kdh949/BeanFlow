package io.github.kdh949.beanflow.identity.internal

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("checks committed customer search audits and injected database failures")
@SpringBootTest
internal class OperationsCustomerSearchIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    private val actor = UUID.randomUUID()

    @BeforeEach fun clean() {
        dropFailure()
        jdbc.execute("TRUNCATE identity_customer_account, operations_operator_permission_grant, operations_audit_record CASCADE")
    }

    @AfterEach fun dropFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS customer_search_audit_failure ON operations_audit_record")
        jdbc.execute("DROP FUNCTION IF EXISTS test_customer_search_audit_failure()")
    }

    @Test fun `canonical exact customer selection returns only masked data with a committed audit`() {
        grant("CUSTOMER_ACCOUNT_SEARCH")
        val customer = customer("minsu01", "김민수")
        customer("minsu012", "다른 고객")
        val response =
            mvc
                .perform(search("  MINSU01  "))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(customer.toString()))
                .andExpect(jsonPath("$.items[0].maskedLoginId").value("m***"))
                .andExpect(jsonPath("$.items[0].maskedDisplayName").value("김*수"))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("minsu01", "김민수", "password", "credential", "accountId", "state")
        val audit = jdbc.queryForMap("SELECT action, audit_category, reason, before_summary, after_summary FROM operations_audit_record")
        assertThat(audit["action"]).isEqualTo("CUSTOMER_ACCOUNT_SEARCHED")
        assertThat(audit["audit_category"]).isEqualTo("PII_ACCESS")
        assertThat(audit["reason"]).isEqualTo("POINT_ACCOUNT_INVESTIGATION")
        assertThat(audit.toString()).doesNotContain("minsu01", "김민수", passwords.dummyHash)
        assertThat(audit["after_summary"].toString()).contains("matchedCount", "1")
    }

    @Test fun `not found is an audited empty result and never a prefix search`() {
        grant("CUSTOMER_ACCOUNT_SEARCH")
        customer("minsu012", "김민수")
        mvc.perform(search("minsu01")).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isEqualTo(1)
    }

    @Test fun `point and support grants cannot replace explicit customer search permission`() {
        customer("minsu01", "김민수")
        mvc.perform(search("minsu01")).andExpect(status().isForbidden)
        listOf("POINT_ACCOUNT_READ", "POINT_ADJUSTMENT", "SUPPORT_SUBJECT_SEARCH").forEach(::grant)
        mvc.perform(search("minsu01")).andExpect(status().isForbidden)
        grant("CUSTOMER_ACCOUNT_SEARCH")
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE permission = 'CUSTOMER_ACCOUNT_SEARCH'",
        )
        mvc.perform(search("minsu01")).andExpect(status().isForbidden)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
    }

    @Test fun `coarse role authentication and request shape are enforced`() {
        grant("CUSTOMER_ACCOUNT_SEARCH")
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("minsu01"))).andExpect(status().isUnauthorized)
        mvc.perform(search("minsu01", "CUSTOMER")).andExpect(status().isForbidden)
        mvc.perform(search("minsu01").queryParam("loginId", "minsu01")).andExpect(status().isBadRequest)
        listOf("", "abc", "minsu%", "ｍinsu01", "a".repeat(101)).forEach {
            mvc.perform(search(it)).andExpect(status().isBadRequest).andExpect(jsonPath("$.items").doesNotExist())
        }
        val unknown =
            mapper.writeValueAsString(
                mapOf(
                    "loginId" to "minsu01",
                    "reasonCode" to "POINT_ACCOUNT_INVESTIGATION",
                    "extra" to "value",
                ),
            )
        mvc.perform(search("minsu01").content(unknown)).andExpect(status().isBadRequest)
        mvc
            .perform(search("minsu01").content("{\"loginId\":\"minsu01\",\"reasonCode\":\"OTHER\"}"))
            .andExpect(status().isBadRequest)
    }

    @Test fun `immediate and deferred audit failure do not disclose a successful search result`() {
        grant("CUSTOMER_ACCOUNT_SEARCH")
        customer("minsu01", "김민수")
        listOf(false, true).forEach { deferred ->
            dropFailure()
            jdbc.execute(
                "CREATE FUNCTION test_customer_search_audit_failure() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION USING ERRCODE = ''23514'', MESSAGE = ''injected audit failure''; END'",
            )
            val timing =
                if (deferred) {
                    "CONSTRAINT TRIGGER customer_search_audit_failure AFTER INSERT ON operations_audit_record DEFERRABLE INITIALLY DEFERRED"
                } else {
                    "TRIGGER customer_search_audit_failure BEFORE INSERT ON operations_audit_record"
                }
            jdbc.execute("CREATE $timing FOR EACH ROW EXECUTE FUNCTION test_customer_search_audit_failure()")
            mvc
                .perform(search("minsu01"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
        }
    }

    @Test fun `repository failure does not become an empty result`() {
        grant("CUSTOMER_ACCOUNT_SEARCH")
        jdbc.execute("ALTER TABLE identity_customer_account RENAME COLUMN display_name TO temporarily_unavailable_name")
        try {
            mvc
                .perform(search("minsu01"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        } finally {
            jdbc.execute("ALTER TABLE identity_customer_account RENAME COLUMN temporarily_unavailable_name TO display_name")
        }
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
                passwords.dummyHash,
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
