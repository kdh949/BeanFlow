package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.CreateLoginSession
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.LoginSessionCoordinator
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Import(
    TestcontainersConfiguration::class,
    OperationsCsrfProbeConfiguration::class,
    BrowserSessionProbeConfiguration::class,
)
@AutoConfigureMockMvc
@SpringBootTest(properties = ["beanflow.toss.client-key=test_ck_auth_foundation"])
class AuthenticationSecurityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val applicationContext: ApplicationContext,
    @Autowired private val sessionCoordinator: LoginSessionCoordinator,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val clock: Clock,
) {
    private val transactions = TransactionTemplate(transactionManager)

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
    fun `unsafe public request is rejected by default csrf protection`() {
        mockMvc
            .perform(post("/api/v1/payment-config"))
            .andExpect(status().isForbidden)
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

    @Test
    fun `unsafe operations request accepts bearer without csrf and rejects browser session cookies`() {
        val path = "/api/v1/operations/security-csrf-probe"
        mockMvc
            .perform(
                post(path).with(
                    jwt()
                        .jwt {
                            it.subject(UUID.randomUUID().toString())
                            it.claim("roles", listOf("PLATFORM_OPERATOR"))
                        }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")),
                ),
            ).andExpect(status().isOk)

        mockMvc
            .perform(post(path))
            .andExpect(status().isUnauthorized)

        listOf(
            Cookie("BEANFLOW_CUSTOMER_SESSION", "customer-session"),
            Cookie("BEANFLOW_MERCHANT_SESSION", "merchant-session"),
        ).forEach { cookie ->
            mockMvc
                .perform(post(path).cookie(cookie))
                .andExpect(status().isForbidden)
        }
    }

    @Test
    fun `same browser uses its matching actor session when both session cookie names are present`() {
        val customerId = UUID.randomUUID()
        val merchantId = UUID.randomUUID()
        val cookies =
            arrayOf(
                sessionCookie("BEANFLOW_CUSTOMER_SESSION", BrowserActorType.CUSTOMER, customerId),
                sessionCookie("BEANFLOW_MERCHANT_SESSION", BrowserActorType.MERCHANT, merchantId),
            )

        mockMvc
            .perform(get("/api/v1/me/security-session-probe").cookie(*cookies))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actorType").value("CUSTOMER"))
            .andExpect(jsonPath("$.actorId").value(customerId.toString()))

        mockMvc
            .perform(get("/api/v1/merchant/security-session-probe").cookie(*cookies))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actorType").value("MERCHANT"))
            .andExpect(jsonPath("$.actorId").value(merchantId.toString()))
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

    private fun sessionCookie(
        name: String,
        actorType: BrowserActorType,
        actorId: UUID,
    ): Cookie {
        val session =
            requireNotNull(
                transactions.execute {
                    sessionCoordinator.create(
                        CreateLoginSession(actorType, actorId, clock.millis(), 1),
                    )
                },
            )
        return Cookie(name, Base64.getEncoder().encodeToString(session.sessionId.toByteArray(Charsets.UTF_8)))
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class OperationsCsrfProbeConfiguration {
    @RestController
    internal class OperationsCsrfProbeController {
        @PostMapping("/api/v1/operations/security-csrf-probe")
        fun mutate() = Unit
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class BrowserSessionProbeConfiguration {
    @Bean
    fun customerBrowserActorLoader(): BrowserActorLoader =
        object : BrowserActorLoader {
            override val actorType = BrowserActorType.CUSTOMER

            override fun load(
                actorId: UUID,
                credentialVersion: Long,
            ) = CustomerActor(actorId)
        }

    @Bean
    fun merchantBrowserActorLoader(): BrowserActorLoader =
        object : BrowserActorLoader {
            override val actorType = BrowserActorType.MERCHANT

            override fun load(
                actorId: UUID,
                credentialVersion: Long,
            ) = MerchantActor(actorId, MerchantAccountState.ACTIVE)
        }

    @RestController
    internal class BrowserSessionProbeController {
        @GetMapping("/api/v1/me/security-session-probe")
        fun customer(actor: CustomerActor) = mapOf("actorType" to "CUSTOMER", "actorId" to actor.actorId)

        @GetMapping("/api/v1/merchant/security-session-probe")
        fun merchant(actor: MerchantActor) = mapOf("actorType" to "MERCHANT", "actorId" to actor.actorId)
    }
}
