package io.github.kdh949.beanflow.identity.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class MerchantAuthenticationOpenApiContractTest {
    @Test
    fun `runtime exposes the implemented merchant session account and credential administration routes`() {
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        listOf(
            "/auth/merchant/sessions:",
            "/auth/merchant/password-changes:",
            "/auth/merchant/sessions/current:",
            "/merchant/me:",
            "/merchant/me/stores:",
            "/operations/merchant-accounts:",
            "/operations/merchant-accounts/{merchantAccountId}/temporary-password-resets:",
            "/operations/merchant-accounts/{merchantAccountId}/lock-releases:",
        ).forEach { route -> assertThat(runtime).contains("  $route") }
        assertThat(runtime).contains("name: BEANFLOW_MERCHANT_SESSION")
    }
}
