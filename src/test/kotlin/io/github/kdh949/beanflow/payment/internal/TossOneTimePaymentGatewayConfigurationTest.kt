package io.github.kdh949.beanflow.payment.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper

internal class TossOneTimePaymentGatewayConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withUserConfiguration(TossOneTimePaymentGatewayConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=toss-sandbox",
                "beanflow.toss.base-url=https://api.tosspayments.com",
            )

    @Test
    fun `individual integration client and secret pair starts the sandbox gateway`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=test_ck_api",
                "beanflow.toss.secret-key=test_sk_api",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PaymentGateway::class.java)
            }
    }

    @Test
    fun `swapped client and secret roles fail startup`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=test_sk_api",
                "beanflow.toss.secret-key=test_ck_api",
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("toss-sandbox requires a Toss API individual integration test client key (test_ck_)")
            }
    }

    @Test
    fun `arbitrary test prefixes fail startup`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=test_client_arbitrary",
                "beanflow.toss.secret-key=test_secret_arbitrary",
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("toss-sandbox requires a Toss API individual integration test client key (test_ck_)")
            }
    }

    @Test
    fun `live keys fail startup in sandbox`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=live_ck_api",
                "beanflow.toss.secret-key=live_sk_api",
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("toss-sandbox requires a Toss API individual integration test client key (test_ck_)")
            }
    }

    @Test
    fun `widget client key fails startup for the Standard Payment Window`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=test_gck_widget",
                "beanflow.toss.secret-key=test_sk_api",
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("toss-sandbox requires a Toss API individual integration test client key (test_ck_)")
            }
    }

    @Test
    fun `widget secret key fails startup for the Standard Payment Window`() {
        contextRunner
            .withPropertyValues(
                "beanflow.toss.client-key=test_ck_api",
                "beanflow.toss.secret-key=test_gsk_widget",
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("toss-sandbox requires a Toss API individual integration test secret key (test_sk_)")
            }
    }
}
