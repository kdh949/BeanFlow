package io.github.kdh949.beanflow.operations.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.FileSystemResource
import org.yaml.snakeyaml.Yaml

internal class PortfolioDeploymentSafetyConfigurationTest {
    @Test
    fun `portfolio profile group activates sandbox providers and enforced Vault validation`() {
        val application =
            FileSystemResource("src/main/resources/application.yaml").inputStream.use { Yaml().load<Map<String, Any>>(it) }
        val spring = application["spring"] as Map<*, *>
        val profiles = spring["profiles"] as Map<*, *>
        val groups = profiles["group"] as Map<*, *>

        assertThat(groups["portfolio"])
            .isEqualTo(listOf("local", "toss-sandbox", "vault-enforced"))
        assertThat(groups["prod"])
            .isEqualTo(listOf("vault-enforced"))
    }

    @Test
    fun `portfolio refuses direct activation without every required profile`() {
        ApplicationContextRunner()
            .withUserConfiguration(PortfolioDeploymentSafetyConfiguration::class.java)
            .withPropertyValues("spring.profiles.active=portfolio,local,toss-sandbox")
            .run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("The portfolio profile requires local, toss-sandbox and vault-enforced profiles")
            }
    }

    @Test
    fun `portfolio refuses production test and local-demo overlap`() {
        listOf("prod", "test", "local-demo").forEach { forbidden ->
            ApplicationContextRunner()
                .withUserConfiguration(PortfolioDeploymentSafetyConfiguration::class.java)
                .withPropertyValues(
                    "spring.profiles.active=portfolio,local,toss-sandbox,vault-enforced,$forbidden",
                ).run { context ->
                    assertThat(context.startupFailure)
                        .hasMessage("The portfolio profile cannot run together with prod, test or local-demo profiles")
                }
        }
    }
}
