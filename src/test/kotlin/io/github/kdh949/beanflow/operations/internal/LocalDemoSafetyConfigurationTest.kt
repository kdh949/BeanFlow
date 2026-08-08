package io.github.kdh949.beanflow.operations.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class LocalDemoSafetyConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(LocalDemoSafetyConfiguration::class.java)

    @Test
    fun `local-demo refuses to start together with the prod profile`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod,local,local-demo")
            .run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(context.startupFailure)
                    .hasMessage("The local-demo profile cannot run together with the prod profile")
            }
    }

    @Test
    fun `local-demo refuses to start without the local sandbox profile`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=local-demo")
            .run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(context.startupFailure)
                    .hasMessage("The local-demo profile requires the local profile so sandbox adapters are active")
            }
    }

    @Test
    fun `local plus local-demo starts and a production profile without local-demo is untouched`() {
        contextRunner.withPropertyValues("spring.profiles.active=local,local-demo").run { context ->
            assertThat(context).hasNotFailed()
        }
        contextRunner.withPropertyValues("spring.profiles.active=prod").run { context ->
            assertThat(context).hasNotFailed()
        }
    }
}
