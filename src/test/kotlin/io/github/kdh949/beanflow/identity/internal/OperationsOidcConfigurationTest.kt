package io.github.kdh949.beanflow.identity.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OperationsOidcConfigurationTest {
    @Test
    fun `public response contains only validated non-secret client settings`() {
        val properties = validProperties()

        val response = OperationsOidcConfigurationController(properties).get()

        assertThat(response).isEqualTo(
            OperationsOidcConfigurationResponse(
                issuerUri = "https://id.beanflow.example/realms/operations",
                authorizationServerUrl = "https://id.beanflow.example",
                realm = "operations",
                clientId = "beanflow-operations-web",
                redirectUri = "https://console.beanflow.example/ops/auth/callback",
                postLogoutRedirectUri = "https://console.beanflow.example/ops",
                scopes = listOf("openid", "profile"),
            ),
        )
    }

    @Test
    fun `issuer must be the exact authorization server realm`() {
        assertThatThrownBy {
            validProperties(issuerUri = "https://attacker.example/realms/operations")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("issuerUri")
    }

    @Test
    fun `offline access and redirect fragments are rejected`() {
        assertThatThrownBy {
            validProperties(scopes = listOf("openid", "offline_access"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("offline_access")

        assertThatThrownBy {
            validProperties(redirectUri = "https://console.beanflow.example/ops/auth/callback#token")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("redirectUri")
    }

    private fun validProperties(
        issuerUri: String = "https://id.beanflow.example/realms/operations",
        redirectUri: String = "https://console.beanflow.example/ops/auth/callback",
        scopes: List<String> = listOf("openid", "profile"),
    ) = OperationsOidcConfigurationProperties(
        issuerUri = issuerUri,
        authorizationServerUrl = "https://id.beanflow.example",
        realm = "operations",
        clientId = "beanflow-operations-web",
        redirectUri = redirectUri,
        postLogoutRedirectUri = "https://console.beanflow.example/ops",
        scopes = scopes,
    )
}
