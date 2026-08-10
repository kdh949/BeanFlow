package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.DeactivatePaymentMethodProviderCommand
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProviderResult
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProvider
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult
import io.github.kdh949.beanflow.payment.api.RegisterPaymentMethodProviderCommand
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

internal class TossSandboxUnavailablePaymentMethodLifecycleAdapter :
    PaymentMethodRegistrationProvider,
    PaymentMethodDeactivationProvider {
    override fun register(command: RegisterPaymentMethodProviderCommand): PaymentMethodRegistrationProviderResult =
        PaymentMethodRegistrationProviderResult.Misconfigured

    override fun deactivate(command: DeactivatePaymentMethodProviderCommand): PaymentMethodDeactivationProviderResult =
        PaymentMethodDeactivationProviderResult.Misconfigured
}

@Configuration(proxyBeanMethods = false)
@Profile("toss-sandbox & !prod")
internal class TossSandboxUnavailablePaymentMethodLifecycleConfiguration {
    @Bean
    fun tossSandboxUnavailablePaymentMethodLifecycleAdapter(): TossSandboxUnavailablePaymentMethodLifecycleAdapter =
        TossSandboxUnavailablePaymentMethodLifecycleAdapter()
}
