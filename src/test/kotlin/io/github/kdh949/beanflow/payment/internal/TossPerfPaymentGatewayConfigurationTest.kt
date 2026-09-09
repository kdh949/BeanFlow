package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.shared.api.ExternalDependencyTelemetry
import io.github.kdh949.beanflow.shared.api.RecordingExternalDependencyTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper

internal class TossPerfPaymentGatewayConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(ExternalDependencyTelemetry::class.java, { RecordingExternalDependencyTelemetry() })
            .withUserConfiguration(TossPerfPaymentGatewayConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=toss-perf",
                "beanflow.toss.client-key=test_ck_perf",
                "beanflow.toss.secret-key=test_sk_perf",
            )

    @Test
    fun `fixed internal driver origin starts the perf gateway`() {
        contextRunner
            .withPropertyValues("beanflow.toss.base-url=http://toss-driver:8080")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PaymentGateway::class.java)
            }
    }

    @Test
    fun `perf profile group excludes the local scripted gateway`() {
        ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(ExternalDependencyTelemetry::class.java, { RecordingExternalDependencyTelemetry() })
            .withUserConfiguration(
                LocalPaymentGatewayConfiguration::class.java,
                TossPerfPaymentGatewayConfiguration::class.java,
            ).withPropertyValues(
                "spring.profiles.active=local,toss-perf,vault-enforced",
                "beanflow.toss.client-key=test_ck_perf",
                "beanflow.toss.secret-key=test_sk_perf",
                "beanflow.toss.base-url=http://toss-driver:8080",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PaymentGateway::class.java)
                assertThat(context.getBean(PaymentGateway::class.java)).isInstanceOf(TossOneTimePaymentGateway::class.java)
            }
    }

    @Test
    fun `arbitrary host path query and user info fail startup`() {
        listOf(
            "http://other-driver:8080",
            "http://toss-driver:8080/v1",
            "http://toss-driver:8080?target=other",
            "http://user@toss-driver:8080",
            "https://toss-driver:8080",
        ).forEach { baseUrl ->
            contextRunner
                .withPropertyValues("beanflow.toss.base-url=$baseUrl")
                .run { context ->
                    assertThat(context.startupFailure)
                        .hasRootCauseMessage("toss-perf requires the fixed internal driver origin http://toss-driver:8080")
                }
        }
    }
}
