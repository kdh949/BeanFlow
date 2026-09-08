package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.shared.api.ExternalDependencyTelemetry
import io.github.kdh949.beanflow.shared.api.RecordingExternalDependencyTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper

internal class TossOneTimePaymentGatewayConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(ExternalDependencyTelemetry::class.java, { RecordingExternalDependencyTelemetry() })
            .withUserConfiguration(TossOneTimePaymentGatewayConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=toss-sandbox",
                "beanflow.toss.base-url=https://api.tosspayments.com",
            )

    @Test
    fun `nonblank keys start the sandbox gateway without prefix validation`() {
        listOf(
            "test_ck_api" to "test_sk_api",
            "test_gck_widget" to "test_gsk_widget",
            "test_gck_widget" to "test_sk_api",
            "test_ck_api" to "test_gsk_widget",
            "live_ck_api" to "live_sk_api",
            "client_fixture" to "secret_fixture",
        ).forEach { (clientKey, secretKey) ->
            contextRunner
                .withPropertyValues(
                    "beanflow.toss.client-key=$clientKey",
                    "beanflow.toss.secret-key=$secretKey",
                ).run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(PaymentGateway::class.java)
                }
        }
    }

    @Test
    fun `blank client key fails startup`() {
        listOf("", " ").forEach { clientKey ->
            contextRunner
                .withPropertyValues(
                    "beanflow.toss.client-key=$clientKey",
                    "beanflow.toss.secret-key=secret_fixture",
                ).run { context ->
                    assertThat(context.startupFailure)
                        .hasRootCauseMessage("toss-sandbox requires a non-blank client key")
                }
        }
    }

    @Test
    fun `blank secret key fails startup`() {
        listOf("", " ").forEach { secretKey ->
            contextRunner
                .withPropertyValues(
                    "beanflow.toss.client-key=client_fixture",
                    "beanflow.toss.secret-key=$secretKey",
                ).run { context ->
                    assertThat(context.startupFailure)
                        .hasRootCauseMessage("toss-sandbox requires a non-blank secret key")
                }
        }
    }

    @Test
    fun `missing client key fails startup`() {
        contextRunner
            .withPropertyValues("beanflow.toss.secret-key=secret_fixture")
            .run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun `missing secret key fails startup`() {
        contextRunner
            .withPropertyValues("beanflow.toss.client-key=client_fixture")
            .run { context ->
                assertThat(context).hasFailed()
            }
    }
}
