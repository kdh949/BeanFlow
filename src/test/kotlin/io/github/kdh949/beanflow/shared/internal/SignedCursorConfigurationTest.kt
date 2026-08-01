package io.github.kdh949.beanflow.shared.internal

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

internal class SignedCursorConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(SignedCursorConfiguration::class.java, TestInfrastructure::class.java)

    @Test
    fun `valid key ring starts and records a closed startup outcome`() {
        contextRunner
            .withPropertyValues(*validProperties())
            .run { context ->
                assertThat(context).hasNotFailed()
                val registry = context.getBean(MeterRegistry::class.java)
                val meter = registry.find("beanflow.pagination.cursor.startup.validation.count").meter()
                assertThat(meter).isNotNull
                assertThat(meter!!.id.tags.map { it.key to it.value }).containsExactly("outcome" to "valid")
            }
    }

    @Test
    fun `missing empty duplicate malformed short and unknown active key configuration fail startup without secret text`() {
        val invalidConfigurations =
            listOf(
                emptyArray(),
                arrayOf("beanflow.pagination.cursor-hmac.active-key-id=current"),
                arrayOf(
                    "beanflow.pagination.cursor-hmac.active-key-id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=$TEST_VECTOR_SECRET",
                    "beanflow.pagination.cursor-hmac.keys[1].id=current",
                    "beanflow.pagination.cursor-hmac.keys[1].secret-base64-url=$PREVIOUS_SECRET",
                ),
                arrayOf(
                    "beanflow.pagination.cursor-hmac.active-key-id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=not+base64url",
                ),
                arrayOf(
                    "beanflow.pagination.cursor-hmac.active-key-id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].id=not a key id",
                    "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=$TEST_VECTOR_SECRET",
                ),
                arrayOf(
                    "beanflow.pagination.cursor-hmac.active-key-id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=c2hvcnQ",
                ),
                arrayOf(
                    "beanflow.pagination.cursor-hmac.active-key-id=missing",
                    "beanflow.pagination.cursor-hmac.keys[0].id=current",
                    "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=$TEST_VECTOR_SECRET",
                ),
            )

        invalidConfigurations.forEach { properties ->
            contextRunner.withPropertyValues(*properties).run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(context.startupFailure).hasMessageContaining("Cursor HMAC configuration is invalid")
                assertThat(context.startupFailure!!.message)
                    .doesNotContain(TEST_VECTOR_SECRET, PREVIOUS_SECRET, "not+base64url", "c2hvcnQ")
            }
        }
    }

    @Test
    fun `main and local runtime configuration do not contain a cursor key or fallback`() {
        val mainConfiguration =
            java.nio.file.Files
                .readString(
                    java.nio.file.Path
                        .of("src/main/resources/application.yaml"),
                )
        val localConfiguration =
            java.nio.file.Files
                .readString(
                    java.nio.file.Path
                        .of("src/main/resources/application-local.yaml"),
                )

        assertThat(mainConfiguration).doesNotContain("cursor-hmac", "BEANFLOW_CURSOR_HMAC")
        assertThat(localConfiguration).doesNotContain("cursor-hmac", "BEANFLOW_CURSOR_HMAC")
    }

    private fun validProperties(): Array<String> =
        arrayOf(
            "beanflow.pagination.cursor-hmac.active-key-id=current",
            "beanflow.pagination.cursor-hmac.keys[0].id=current",
            "beanflow.pagination.cursor-hmac.keys[0].secret-base64-url=$TEST_VECTOR_SECRET",
        )

    @Configuration(proxyBeanMethods = false)
    internal class TestInfrastructure {
        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    private companion object {
        const val TEST_VECTOR_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
        const val PREVIOUS_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA"
    }
}
