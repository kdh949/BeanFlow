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
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

internal class ScriptedPaymentMethodLifecycleAdapter :
    PaymentMethodRegistrationProvider,
    PaymentMethodDeactivationProvider {
    val registrationCalls = AtomicInteger()
    val deactivationCalls = AtomicInteger()
    @Volatile
    var observedActiveTransaction: Boolean = false

    override fun register(command: RegisterPaymentMethodProviderCommand): PaymentMethodRegistrationProviderResult {
        registrationCalls.incrementAndGet()
        observedActiveTransaction = observedActiveTransaction || TransactionSynchronizationManager.isActualTransactionActive()
        return when {
            command.authorizationKey.startsWith("rejected:") ->
                PaymentMethodRegistrationProviderResult.RejectedWithoutEffect

            command.authorizationKey.startsWith("unknown:") -> PaymentMethodRegistrationProviderResult.Unknown
            command.authorizationKey.startsWith("misconfigured:") ->
                PaymentMethodRegistrationProviderResult.Misconfigured

            else -> {
                val digest = sha256(command.authorizationKey)
                val tokenPrefix =
                    when {
                        command.authorizationKey.startsWith("deactivate-rejected:") -> "scripted_rejected_"
                        command.authorizationKey.startsWith("deactivate-unknown:") -> "scripted_unknown_"
                        command.authorizationKey.startsWith("deactivate-misconfigured:") -> "scripted_misconfigured_"
                        else -> "scripted_ok_"
                    }
                PaymentMethodRegistrationProviderResult.Issued(
                    tokenReference = tokenPrefix + digest.take(48),
                    cardBrand = "VISA",
                    lastFour = digest.takeLast(4).map { ((it.code % 10) + '0'.code).toChar() }.joinToString(""),
                )
            }
        }
    }

    override fun deactivate(command: DeactivatePaymentMethodProviderCommand): PaymentMethodDeactivationProviderResult {
        deactivationCalls.incrementAndGet()
        observedActiveTransaction = observedActiveTransaction || TransactionSynchronizationManager.isActualTransactionActive()
        return when {
            command.tokenReference.startsWith("scripted_rejected_") ->
                PaymentMethodDeactivationProviderResult.RejectedWithoutEffect

            command.tokenReference.startsWith("scripted_unknown_") -> PaymentMethodDeactivationProviderResult.Unknown
            command.tokenReference.startsWith("scripted_misconfigured_") ->
                PaymentMethodDeactivationProviderResult.Misconfigured

            else -> PaymentMethodDeactivationProviderResult.Deactivated
        }
    }

    fun reset() {
        registrationCalls.set(0)
        deactivationCalls.set(0)
        observedActiveTransaction = false
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@Configuration(proxyBeanMethods = false)
@Profile("(local | test) & !toss-sandbox & !prod")
internal class ScriptedPaymentMethodLifecycleConfiguration {
    @Bean
    fun scriptedPaymentMethodLifecycleAdapter(): ScriptedPaymentMethodLifecycleAdapter =
        ScriptedPaymentMethodLifecycleAdapter()
}
