package io.github.kdh949.beanflow.identity.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

internal class CustomerAuthenticationOpenApiContractTest {
    @Test
    fun `runtime exposes exactly the implemented customer account and session routes`() {
        val runtime = Path.of("openapi/beanflow-v1-runtime.yaml").readText()
        listOf(
            "/auth/customer/registrations:",
            "/auth/customer/sessions:",
            "/auth/customer/sessions/current:",
            "/me:",
        ).forEach { route -> assertThat(runtime).contains("  $route") }
        assertThat(runtime).contains("name: BEANFLOW_CUSTOMER_SESSION")
        assertThat(runtime).contains("  /me:\n    \$ref: \"./beanflow-v1.yaml#/paths/~1me\"")
    }

    @Test
    fun `current customer contract documents actor credential mismatch as forbidden`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val currentCustomer = target.substringAfter("  /me:\n").substringBefore("  /merchant/me:\n")

        assertThat(currentCustomer)
            .contains("Bearer 또는 Merchant 전용 credential처럼 다른 인증 chain의")
            .contains("`403 ACCESS_DENIED`")
            .contains("\"403\": { \$ref: \"#/components/responses/Forbidden\" }")
    }

    @Test
    fun `forbidden contract keeps a missing csrf token distinct from a presented invalid token`() {
        val target = Path.of("openapi/beanflow-v1.yaml").readText()
        val forbidden = target.substringAfter("    Forbidden:\n").substringBefore("    DependencyUnavailable:\n")

        assertThat(forbidden)
            .contains("missing CSRF token fails with `ACCESS_DENIED`")
            .contains("presented but stale or invalid CSRF token fails with `CSRF_TOKEN_INVALID`")
    }
}
