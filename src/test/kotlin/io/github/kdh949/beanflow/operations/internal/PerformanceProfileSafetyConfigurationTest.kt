package io.github.kdh949.beanflow.operations.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.FileSystemResource
import org.yaml.snakeyaml.Yaml

internal class PerformanceProfileSafetyConfigurationTest {
    @Test
    fun `perf profile group activates local driver and enforced Vault`() {
        val application =
            FileSystemResource("src/main/resources/application.yaml").inputStream.use { Yaml().load<Map<String, Any>>(it) }
        val spring = application["spring"] as Map<*, *>
        val profiles = spring["profiles"] as Map<*, *>
        val groups = profiles["group"] as Map<*, *>

        assertThat(groups["perf"]).isEqualTo(listOf("local", "toss-perf", "vault-enforced"))
    }

    @Test
    fun `perf refuses direct activation without every required profile`() {
        ApplicationContextRunner()
            .withUserConfiguration(PerformanceProfileSafetyConfiguration::class.java)
            .withPropertyValues("spring.profiles.active=perf,local,toss-perf")
            .run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("The perf profile requires local, toss-perf and vault-enforced profiles")
            }
    }

    @Test
    fun `perf refuses production portfolio and test fixture overlap`() {
        listOf("prod", "portfolio", "test", "local-demo", "toss-sandbox").forEach { forbidden ->
            ApplicationContextRunner()
                .withUserConfiguration(PerformanceProfileSafetyConfiguration::class.java)
                .withPropertyValues(
                    "spring.profiles.active=perf,local,toss-perf,vault-enforced,$forbidden",
                ).run { context ->
                    assertThat(context.startupFailure)
                        .hasMessage(
                            "The perf profile cannot run together with prod, portfolio, test, local-demo or toss-sandbox profiles",
                        )
                }
        }
    }

    @Test
    fun `perf management listener is separate and exposes only health and prometheus`() {
        val application =
            FileSystemResource("src/main/resources/application-perf.yaml").inputStream.use { Yaml().load<Map<String, Any>>(it) }
        val management = application["management"] as Map<*, *>
        val server = management["server"] as Map<*, *>
        val endpoints = management["endpoints"] as Map<*, *>
        val web = endpoints["web"] as Map<*, *>
        val exposure = web["exposure"] as Map<*, *>

        assertThat(server["port"]).isEqualTo("\${BEANFLOW_MANAGEMENT_PORT:8081}")
        assertThat(server["address"]).isEqualTo("\${BEANFLOW_MANAGEMENT_ADDRESS:0.0.0.0}")
        assertThat(exposure["include"]).isEqualTo("health,prometheus")
    }
}
