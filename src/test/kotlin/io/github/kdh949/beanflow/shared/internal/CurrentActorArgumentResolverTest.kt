package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.context.request.ServletWebRequest
import java.util.UUID

internal class CurrentActorArgumentResolverTest {
    private val resolver = CurrentActorArgumentResolver()

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `JWT authentication becomes an OperatorActor without exposing Jwt to the controller`() {
        val actorId = UUID.randomUUID()
        val jwt =
            Jwt
                .withTokenValue("operator-token")
                .header("alg", "RS256")
                .subject(actorId.toString())
                .claim("roles", listOf("PLATFORM_OPERATOR", "SUPPORT_R2"))
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)

        val actor = resolver.resolveArgument(parameter("operator", OperatorActor::class.java), null, webRequest(), null)

        assertThat(actor).isEqualTo(
            OperatorActor(actorId, setOf("PLATFORM_OPERATOR", "SUPPORT_R2")),
        )
    }

    @Test
    fun `synthetic test JWT roles resolve to browser actor types without exposing Jwt to controllers`() {
        val customerId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt(customerId, "CUSTOMER"))
        assertThat(resolver.resolveArgument(parameter("customer", CustomerActor::class.java), null, webRequest(), null))
            .isEqualTo(CustomerActor(customerId))

        val merchantId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt(merchantId, "STORE_OWNER"))
        assertThat(resolver.resolveArgument(parameter("merchant", MerchantActor::class.java), null, webRequest(), null))
            .isEqualTo(MerchantActor(merchantId, MerchantAccountState.ACTIVE))
    }

    @Test
    fun `validated browser authentication returns the exact current actor`() {
        val customer = CustomerActor(UUID.randomUUID())
        SecurityContextHolder.getContext().authentication = BrowserSessionAuthenticationToken(customer)

        assertThat(resolver.resolveArgument(parameter("customer", CustomerActor::class.java), null, webRequest(), null))
            .isEqualTo(customer)

        val merchant = MerchantActor(UUID.randomUUID(), MerchantAccountState.INITIAL_PASSWORD)
        SecurityContextHolder.getContext().authentication = BrowserSessionAuthenticationToken(merchant)

        assertThat(resolver.resolveArgument(parameter("merchant", MerchantActor::class.java), null, webRequest(), null))
            .isEqualTo(merchant)
    }

    @Test
    fun `authenticated actor type mismatch is access denied`() {
        SecurityContextHolder.getContext().authentication =
            BrowserSessionAuthenticationToken(CustomerActor(UUID.randomUUID()))

        assertThatThrownBy {
            resolver.resolveArgument(parameter("merchant", MerchantActor::class.java), null, webRequest(), null)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
        }
    }

    @Test
    fun `resolver supports only CurrentActor parameters`() {
        assertThat(resolver.supportsParameter(parameter("current", CurrentActor::class.java))).isTrue()
        assertThat(resolver.supportsParameter(parameter("other", UUID::class.java))).isFalse()
    }

    private fun parameter(
        methodName: String,
        type: Class<*>,
    ): MethodParameter =
        MethodParameter(
            TestController::class.java.getDeclaredMethod(methodName, type),
            0,
        )

    private fun webRequest() = ServletWebRequest(MockHttpServletRequest())

    private fun jwt(
        actorId: UUID,
        role: String,
    ): Jwt =
        Jwt
            .withTokenValue("synthetic-test-token")
            .header("alg", "RS256")
            .subject(actorId.toString())
            .claim("roles", listOf(role))
            .build()

    @Suppress("UNUSED_PARAMETER")
    private class TestController {
        fun current(actor: CurrentActor) = Unit

        fun operator(actor: OperatorActor) = Unit

        fun customer(actor: CustomerActor) = Unit

        fun merchant(actor: MerchantActor) = Unit

        fun other(actorId: UUID) = Unit
    }
}
