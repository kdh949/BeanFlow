package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowSharedDatabaseTest
@SpringBootTest
internal class OperatorDirectoryIntegrationTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
        private val jdbc: JdbcTemplate,
        private val directory: OperatorDirectoryService,
    ) {
        private val reader = UUID.fromString("99010000-0000-4000-8000-000000000001")
        private val first = UUID.fromString("99010000-0000-4000-8000-000000000002")
        private val second = UUID.fromString("99010000-0000-4000-8000-000000000003")
        private val unobserved = UUID.fromString("99010000-0000-4000-8000-000000000004")
        private val issuedAt = Instant.parse("2026-09-11T00:00:00Z")

        @BeforeEach
        fun prepare() {
            jdbc.update("DELETE FROM operations_operator_login_display")
            jdbc.update("DELETE FROM operations_operator_permission_grant")
            grant(reader, "SUPPORT_CASE_READ")
        }

        @Test
        fun `signed actor login observations cannot relabel another actor or overwrite a newer observation`() {
            mvc
                .perform(
                    get("/api/v1/operations/me")
                        .with(actor(first, "minji.new", issuedAt.plusSeconds(10)))
                        .param("operatorId", second.toString())
                        .param("loginName", "forged"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.display.loginName").value("minji.new"))
            mvc
                .perform(get("/api/v1/operations/me").with(actor(first, "minji.old", issuedAt)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.display.loginName").value("minji.new"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_operator_login_display", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT actor_id FROM operations_operator_login_display", UUID::class.java)).isEqualTo(first)
        }

        @Test
        fun `missing identity labels stay explicit and malformed claims are rejected without a profile`() {
            mvc
                .perform(get("/api/v1/operations/me").with(actor(first)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.display.state").value("MISSING_PROFILE"))
            mvc.perform(get("/api/v1/operations/me").with(actor(first, " "))).andExpect(status().isServiceUnavailable)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_operator_login_display", Long::class.java)).isZero()
        }

        @Test
        fun `assignment purposes require all current grants while history includes revoked former assignees`() {
            directory.observe(first, "minji.support", issuedAt)
            directory.observe(second, "jiyun.support", issuedAt)
            grant(first, "SUPPORT_CASE_WRITE")
            grant(second, "SUPPORT_CASE_WRITE", "SUPPORT_ACTION_EXECUTE", "SUPPORT_ORDER_CANCEL")
            mvc
                .perform(get(PATH).with(actor(reader)).param("purpose", "ORDER_CANCELLATION"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].loginName").value("jiyun.support"))
            jdbc.update("UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?", second)
            mvc
                .perform(get(PATH).with(actor(reader)).param("purpose", "ORDER_CANCELLATION"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
            mvc
                .perform(get(PATH).with(actor(reader)).param("purpose", "CASE_FILTER"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
            mvc.perform(get(PATH).with(actor(unobserved))).andExpect(status().isForbidden)
        }

        @Test
        fun `directory pagination is bound to actor purpose and literal name query and counts missing profiles`() {
            directory.observe(first, "agent_one", issuedAt)
            directory.observe(second, "agentTwo", issuedAt)
            grant(first, "SUPPORT_CASE_WRITE")
            grant(second, "SUPPORT_CASE_WRITE", "SUPPORT_CASE_READ")
            grant(unobserved, "SUPPORT_CASE_WRITE")
            val response =
                mvc
                    .perform(get(PATH).with(actor(reader)).param("limit", "1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.missingProfileCount").value(1))
                    .andReturn()
                    .response.contentAsString
            val cursor =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(response)["nextCursor"]
                    .stringValue()
            mvc
                .perform(get(PATH).with(actor(reader)).param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].operatorId").value(second.toString()))
            mvc.perform(get(PATH).with(actor(second)).param("cursor", cursor)).andExpect(status().isBadRequest)
            mvc
                .perform(get(PATH).with(actor(reader)).param("purpose", "CASE_FILTER").param("cursor", cursor))
                .andExpect(status().isBadRequest)
            mvc.perform(get(PATH).with(actor(reader)).param("query", "agent").param("cursor", cursor)).andExpect(status().isBadRequest)
            mvc
                .perform(get(PATH).with(actor(reader)).param("query", "_"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].loginName").value("agent_one"))
        }

        @Test
        fun `concurrent login observations preserve the latest signed token timestamp`() {
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(2).use { pool ->
                val futures =
                    (1L..2L).map { version ->
                        pool.submit<Any> {
                            start.await()
                            directory.observe(first, "agent-$version", issuedAt.plusSeconds(version))
                        }
                    }
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            }
            assertThat(directory.displays(setOf(first)).getValue(first).loginName).isEqualTo("agent-2")
        }

        private fun grant(
            id: UUID,
            vararg names: String,
        ) {
            names.forEach { name ->
                jdbc.update(
                    "INSERT INTO operations_operator_permission_grant " +
                        "(actor_id, permission, state, granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', now(), 1, ?)",
                    id,
                    name,
                    "operator-directory-test:$id:$name",
                )
            }
        }

        private fun actor(
            id: UUID,
            name: String? = null,
            tokenAt: Instant = issuedAt,
        ) = jwt()
            .jwt {
                it.subject(id.toString()).issuedAt(tokenAt).claim("roles", listOf("PLATFORM_OPERATOR"))
                if (name != null) it.claim("preferred_username", name)
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private companion object {
            const val PATH = "/api/v1/operations/operator-directory"
        }
    }
