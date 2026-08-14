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
    }
}
