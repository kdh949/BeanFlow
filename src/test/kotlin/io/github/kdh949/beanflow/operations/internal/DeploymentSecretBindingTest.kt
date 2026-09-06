package io.github.kdh949.beanflow.operations.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64

internal class DeploymentSecretBindingTest {
    @Test
    fun `deployment profiles use the supplied authentication HMAC key despite including local`() {
        val supplied = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 42 })
        listOf("portfolio", "perf").forEach { profile ->
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                    "spring.config.location=file:src/main/resources/",
                    "spring.profiles.active=$profile",
                    "BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL=$supplied",
                ).run { context ->
                    assertThat(context.environment.getProperty("beanflow.authentication.attempt-hmac-key-base64-url"))
                        .isEqualTo(supplied)
                }
        }
    }

    @Test
    fun `deployment profiles cannot use the local key when their secret is missing`() {
        listOf("portfolio", "perf").forEach { profile ->
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/", "spring.profiles.active=$profile")
                .run { context ->
                    assertThatThrownBy {
                        context.environment.getProperty("beanflow.authentication.attempt-hmac-key-base64-url")
                    }.isInstanceOf(IllegalArgumentException::class.java)
                        .hasMessageContaining("BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL")
                }
        }
    }

    @Test
    fun `standalone local development retains its explicit fixture key`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=file:src/main/resources/", "spring.profiles.active=local")
            .run { context ->
                assertThat(context.environment.getProperty("beanflow.authentication.attempt-hmac-key-base64-url"))
                    .isNotBlank()
            }
    }
}
