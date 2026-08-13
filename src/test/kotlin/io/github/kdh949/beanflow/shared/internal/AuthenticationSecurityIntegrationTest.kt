package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = ["beanflow.toss.client-key=test_ck_auth_foundation"])
class AuthenticationSecurityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val applicationContext: ApplicationContext,
) {
    @Test
    fun `exactly four security filter chains are registered`() {
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain::class.java).keys)
            .containsExactlyInAnyOrder(
                "publicSecurityFilterChain",
                "operationsSecurityFilterChain",
                "merchantSecurityFilterChain",
                "customerSecurityFilterChain",
            )
    }

    @Test
    fun `public endpoint is anonymous and every actor endpoint is protected`() {
        mockMvc
            .perform(get("/api/v1/payment-config"))
            .andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/operations/me"))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/api/v1/stores/${UUID.randomUUID()}/settlements"))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/api/v1/point-accounts/${UUID.randomUUID()}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `csrf cookies are actor specific secure readable cookies`() {
        val customer = issueCsrf("customer", "BEANFLOW_CUSTOMER_XSRF")
        val merchant = issueCsrf("merchant", "BEANFLOW_MERCHANT_XSRF")

        assertCsrfCookie(customer)
        assertCsrfCookie(merchant)
        assertThat(customer.value).isNotEqualTo(merchant.value)
    }

    @Test
    fun `unsafe browser request accepts only its actor csrf cookie and header`() {
        val customer = issueCsrf("customer", "BEANFLOW_CUSTOMER_XSRF")
        val merchant = issueCsrf("merchant", "BEANFLOW_MERCHANT_XSRF")

        mockMvc
            .perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .cookie(customer)
                    .header("X-BEANFLOW-CSRF", customer.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .cookie(merchant)
                    .header("X-BEANFLOW-CSRF", merchant.value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `operations jwt works without csrf and cross actor credentials are forbidden`() {
        val operatorId = UUID.randomUUID()
        mockMvc
            .perform(
                get("/api/v1/operations/me").with(
                    jwt()
                        .jwt {
                            it.subject(operatorId.toString())
                            it.claim("roles", listOf("PLATFORM_OPERATOR"))
                        }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actorType").value("OPERATOR"))
            .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))

        mockMvc
            .perform(
                get("/api/v1/operations/me")
                    .cookie(Cookie("BEANFLOW_CUSTOMER_SESSION", "customer-session")),
            ).andExpect(status().isForbidden)

        mockMvc
            .perform(
                get("/api/v1/point-accounts/${UUID.randomUUID()}")
                    .header("Authorization", "Bearer operator-token"),
            ).andExpect(status().isForbidden)
    }

    private fun issueCsrf(
        actor: String,
        cookieName: String,
    ): Cookie {
        val result =
            mockMvc
                .perform(get("/api/v1/auth/$actor/csrf"))
                .andExpect(status().isNoContent)
                .andReturn()
        return requireNotNull(result.response.getCookie(cookieName))
    }

    private fun assertCsrfCookie(cookie: Cookie) {
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.isHttpOnly).isFalse()
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax")
    }
}
