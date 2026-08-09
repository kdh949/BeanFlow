package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.DeactivatePaymentMethodProviderCommand
import io.github.kdh949.beanflow.payment.api.PaymentMethodDeactivationProviderResult
import io.github.kdh949.beanflow.payment.api.PaymentMethodRegistrationProviderResult
import io.github.kdh949.beanflow.payment.api.RegisterPaymentMethodProviderCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ScriptedPaymentMethodLifecycleAdapterTest {
    private val adapter = ScriptedPaymentMethodLifecycleAdapter()
    private val providerReference = "bf_${"a".repeat(43)}"

    @Test
    fun `registration variants are deterministic closed results`() {
        val issued = adapter.register(command("issued:card"))
        val replay = adapter.register(command("issued:card"))

        assertThat(issued).isInstanceOf(PaymentMethodRegistrationProviderResult.Issued::class.java)
        assertThat(replay).isEqualTo(issued)
        assertThat(adapter.register(command("rejected:card")))
            .isEqualTo(PaymentMethodRegistrationProviderResult.RejectedWithoutEffect)
        assertThat(adapter.register(command("unknown:card")))
            .isEqualTo(PaymentMethodRegistrationProviderResult.Unknown)
        assertThat(adapter.register(command("misconfigured:card")))
            .isEqualTo(PaymentMethodRegistrationProviderResult.Misconfigured)
    }

    @Test
    fun `deactivation variants are deterministic closed results`() {
        assertThat(adapter.deactivate(deactivation("scripted_ok_token")))
            .isEqualTo(PaymentMethodDeactivationProviderResult.Deactivated)
        assertThat(adapter.deactivate(deactivation("scripted_rejected_token")))
            .isEqualTo(PaymentMethodDeactivationProviderResult.RejectedWithoutEffect)
        assertThat(adapter.deactivate(deactivation("scripted_unknown_token")))
            .isEqualTo(PaymentMethodDeactivationProviderResult.Unknown)
        assertThat(adapter.deactivate(deactivation("scripted_misconfigured_token")))
            .isEqualTo(PaymentMethodDeactivationProviderResult.Misconfigured)
    }

    private fun command(authKey: String) =
        RegisterPaymentMethodProviderCommand(
            authorizationKey = authKey,
            providerCustomerReference = providerReference,
        )

    private fun deactivation(token: String) = DeactivatePaymentMethodProviderCommand(token, providerReference)
}
