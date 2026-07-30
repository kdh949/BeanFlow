package io.github.kdh949.beanflow.notification.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class NotificationProviderSafetyConfigurationTest {
    @Test
    fun `scripted provider fails startup in production profile`() {
        ApplicationContextRunner()
            .withUserConfiguration(NotificationProviderSafetyConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=prod",
                "beanflow.notification.provider=scripted",
            ).run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(context.startupFailure)
                    .hasMessage("Scripted notification provider cannot run in the prod profile")
            }
    }

    @Test
    fun `scripted provider is permitted only outside production`() {
        ApplicationContextRunner()
            .withUserConfiguration(NotificationProviderSafetyConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=local",
                "beanflow.notification.provider=scripted",
            ).run { context ->
                assertThat(context).hasNotFailed()
            }
    }
}
