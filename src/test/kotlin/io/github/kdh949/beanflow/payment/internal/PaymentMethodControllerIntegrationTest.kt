package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.tamperSignedCursorSignature
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@BeanflowSharedDatabaseTest
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
internal class PaymentMethodControllerIntegrationTest(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
    private val queries: PaymentMethodQueryService,
) {
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                operations_audit_record,
                payment_method_default_command,
                payment_method_deactivation,
                payment_method_registration,
                payment_provider_notification_inbox,
                payment_provider_request_snapshot,
                payment_payment,
                payment_method
            CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `customer registers lists defaults and deactivates without exposing internal references`() {
        val customerId = UUID.randomUUID()
        val methodId = register(customerId, "api-register-key", "issued:api", "API card")

        mockMvc
            .perform(get("/api/v1/payment-methods").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].paymentMethodId").value(methodId.toString()))
            .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.items[0].noticeCode").doesNotExist())
            .andExpect(content().string(not(containsString("tokenReference"))))
            .andExpect(content().string(not(containsString("providerCustomerReference"))))

        mockMvc
            .perform(
                put("/api/v1/payment-methods/{id}/default", methodId)
                    .header("Idempotency-Key", "api-default-key")
                    .with(customerJwt(customerId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.isDefault").value(true))

        mockMvc
            .perform(
                delete("/api/v1/payment-methods/{id}", methodId)
                    .header("Idempotency-Key", "api-delete-key")
                    .with(customerJwt(customerId)),
            ).andExpect(status().isNoContent)
            .andExpect(header().doesNotExist("Content-Type"))
            .andExpect(content().string(""))

        mockMvc
            .perform(get("/api/v1/payment-methods").with(customerJwt(customerId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))
    }

    @Test
    fun `registration rejects unknown and sensitive request fields`() {
        val customerId = UUID.randomUUID()
        listOf(
            """{"authKey":"issued:x","displayAlias":"Card","provider":"OTHER"}""",
            """{"authKey":"issued:x","displayAlias":"Card","cardNumber":"4242424242424242"}""",
            """{"authKey":"issued:x","displayAlias":"   "}""",
        ).forEachIndexed { index, body ->
            mockMvc
                .perform(
                    post("/api/v1/payment-methods")
                        .header("Idempotency-Key", "invalid-key-$index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        org.assertj.core.api.Assertions
            .assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM payment_method_registration", Long::class.java),
            ).isZero()
    }

    @Test
    fun `registration validation never reflects an oversized authorization credential`() {
        val customerId = UUID.randomUUID()
        val marker = "AUTH_KEY_MARKER_MUST_NOT_BE_REFLECTED"
        val oversizedAuthKey = marker + "x".repeat(300)

        mockMvc
            .perform(
                post("/api/v1/payment-methods")
                    .header("Idempotency-Key", "oversized-auth-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"authKey":"$oversizedAuthKey","displayAlias":"Card"}""")
                    .with(customerJwt(customerId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request validation failed"))
            .andExpect(jsonPath("$.details[0].field").value("authKey"))
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_VALUE"))
            .andExpect(content().string(not(containsString(marker))))
            .andExpect(content().string(not(containsString("rejected value"))))
        org.assertj.core.api.Assertions
            .assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM payment_method_registration", Long::class.java),
            ).isZero()
    }

    @Test
    fun `role and ownership checks return stable authentication authorization and resource codes`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val methodId = register(owner, "owner-register-key", "issued:owner", "Owner card")

        mockMvc.perform(get("/api/v1/payment-methods")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/api/v1/payment-methods").with(roleJwt(owner, "STORE_OWNER")))
            .andExpect(status().isForbidden)
        mockMvc
            .perform(
                delete("/api/v1/payment-methods/{id}", methodId)
                    .header("Idempotency-Key", "other-delete-key")
                    .with(customerJwt(other)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        mockMvc
            .perform(
                delete("/api/v1/payment-methods/{id}", UUID.randomUUID())
                    .header("Idempotency-Key", "missing-delete-key")
                    .with(customerJwt(owner)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        mockMvc
            .perform(get("/api/v1/payment-methods").with(customerJwt("not-a-uuid")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    fun `signed cursor is owner bound ordered and rejects tamper and invalid limits`() {
        val customerId = UUID.randomUUID()
        val other = UUID.randomUUID()
        val firstId = register(customerId, "cursor-register-1", "issued:cursor-1", "First")
        register(customerId, "cursor-register-2", "issued:cursor-2", "Second")
        mockMvc
            .perform(
                put("/api/v1/payment-methods/{id}/default", firstId)
                    .header("Idempotency-Key", "cursor-default-key")
                    .with(customerJwt(customerId)),
            ).andExpect(status().isOk)

        val firstPage =
            mockMvc
                .perform(get("/api/v1/payment-methods").param("limit", "1").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].paymentMethodId").value(firstId.toString()))
                .andReturn()
                .response
                .contentAsString
        val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(firstPage)!!.groupValues[1]

        org.assertj.core.api.Assertions
            .assertThat(queries.list(customerId, cursor, "1").items)
            .hasSize(1)

        mockMvc
            .perform(
                get("/api/v1/payment-methods")
                    .param("limit", "1")
                    .param("cursor", cursor)
                    .with(customerJwt(customerId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
        mockMvc
            .perform(
                get("/api/v1/payment-methods")
                    .param("cursor", tamperSignedCursorSignature(cursor))
                    .with(customerJwt(customerId)),
            ).andExpect(status().isBadRequest)
        mockMvc
            .perform(get("/api/v1/payment-methods").param("cursor", cursor).with(customerJwt(other)))
            .andExpect(status().isBadRequest)
        listOf("0", "101", "1.5", "-1").forEach { limit ->
            mockMvc
                .perform(get("/api/v1/payment-methods").param("limit", limit).with(customerJwt(customerId)))
                .andExpect(status().isBadRequest)
        }
    }

    private fun register(
        customerId: UUID,
        key: String,
        authKey: String,
        alias: String,
    ): UUID {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/payment-methods")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"authKey":"$authKey","displayAlias":"$alias"}""")
                        .with(customerJwt(customerId)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return UUID.fromString(Regex("\"paymentMethodId\":\"([^\"]+)\"").find(body)!!.groupValues[1])
    }

    private fun customerJwt(customerId: UUID) = customerJwt(customerId.toString())

    private fun customerJwt(subject: String) = roleJwt(subject, "CUSTOMER")

    private fun roleJwt(
        actorId: UUID,
        role: String,
    ) = roleJwt(actorId.toString(), role)

    private fun roleJwt(
        subject: String,
        role: String,
    ) = jwt()
        .jwt { it.subject(subject).claim("roles", listOf(role)) }
        .authorities(SimpleGrantedAuthority("ROLE_$role"))
}
