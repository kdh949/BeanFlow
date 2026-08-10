package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
internal class PaymentMethodProviderSafetyConfiguration {
    @Bean
    fun paymentMethodProviderSafetyGuard(
        environment: Environment,
        registrationProviders: List<PaymentMethodRegistrationProvider>,
        deactivationProviders: List<PaymentMethodDeactivationProvider>,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val profiles = environment.activeProfiles.toSet()
            val scriptedSelected =
                registrationProviders.any { it is ScriptedPaymentMethodLifecycleAdapter } ||
                    deactivationProviders.any { it is ScriptedPaymentMethodLifecycleAdapter }
            if ("prod" in profiles && scriptedSelected) {
                error("Scripted payment method lifecycle provider cannot run in the prod profile")
            }
            if ("prod" in profiles && profiles.any { it in setOf("local", "test", "toss-sandbox") }) {
                error("Payment method lifecycle provider profiles overlap with prod")
            }
            if ("toss-sandbox" in profiles && scriptedSelected) {
                error("Scripted and toss-sandbox payment method lifecycle profiles overlap")
            }
            if (registrationProviders.size != 1 || deactivationProviders.size != 1) {
                error("Exactly one payment method registration and deactivation provider is required")
            }
        }
}
