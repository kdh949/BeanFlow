package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProviderResult
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult
import io.github.kdh949.beanflow.payment.api.DeactivatePaymentMethodProviderCommand
import io.github.kdh949.beanflow.payment.api.RegisterPaymentMethodProviderCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal class PaymentMethodProviderSafetyConfigurationTest {
    @Test
    fun `explicit local scripted provider succeeds`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                PaymentMethodProviderSafetyConfiguration::class.java,
                ScriptedPaymentMethodLifecycleConfiguration::class.java,
            ).withPropertyValues("spring.profiles.active=local")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `missing provider fails startup without fallback`() {
        ApplicationContextRunner()
            .withUserConfiguration(PaymentMethodProviderSafetyConfiguration::class.java)
            .run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("Exactly one payment method registration and deactivation provider is required")
            }
    }

    @Test
    fun `local toss sandbox runtime selects an explicit unavailable payment method lifecycle provider`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                PaymentMethodProviderSafetyConfiguration::class.java,
                TossSandboxUnavailablePaymentMethodLifecycleConfiguration::class.java,
            ).withPropertyValues("spring.profiles.active=toss-sandbox-runtime,local,toss-sandbox")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PaymentMethodRegistrationProvider::class.java)
                assertThat(context).hasSingleBean(PaymentMethodDeactivationProvider::class.java)

                val provider = context.getBean(TossSandboxUnavailablePaymentMethodLifecycleAdapter::class.java)
                assertThat(
                    provider.register(RegisterPaymentMethodProviderCommand("unused", "unused")),
                ).isEqualTo(PaymentMethodRegistrationProviderResult.Misconfigured)
                assertThat(
                    provider.deactivate(DeactivatePaymentMethodProviderCommand("unused", "unused")),
                ).isEqualTo(PaymentMethodDeactivationProviderResult.Misconfigured)
            }
    }

    @Test
    fun `scripted provider fails startup in production`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                PaymentMethodProviderSafetyConfiguration::class.java,
                ExplicitScriptedProviderConfiguration::class.java,
            ).withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("Scripted payment method lifecycle provider cannot run in the prod profile")
            }
    }

    @Test
    fun `multiple providers and profile overlap fail startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                PaymentMethodProviderSafetyConfiguration::class.java,
                MultipleProviderConfiguration::class.java,
            ).run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("Exactly one payment method registration and deactivation provider is required")
            }

        ApplicationContextRunner()
            .withUserConfiguration(
                PaymentMethodProviderSafetyConfiguration::class.java,
                ExplicitScriptedProviderConfiguration::class.java,
            ).withPropertyValues("spring.profiles.active=local,toss-sandbox")
            .run { context ->
                assertThat(context.startupFailure)
                    .hasMessage("Scripted and toss-sandbox payment method lifecycle profiles overlap")
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class ExplicitScriptedProviderConfiguration {
        @Bean
        fun scripted(): ScriptedPaymentMethodLifecycleAdapter = ScriptedPaymentMethodLifecycleAdapter()
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleProviderConfiguration {
        @Bean
        fun scripted(): ScriptedPaymentMethodLifecycleAdapter = ScriptedPaymentMethodLifecycleAdapter()

        @Bean
        fun secondRegistration(): PaymentMethodRegistrationProvider =
            PaymentMethodRegistrationProvider { PaymentMethodRegistrationProviderResult.Unknown }

        @Bean
        fun secondDeactivation(): PaymentMethodDeactivationProvider =
            PaymentMethodDeactivationProvider { PaymentMethodDeactivationProviderResult.Unknown }
    }
}
