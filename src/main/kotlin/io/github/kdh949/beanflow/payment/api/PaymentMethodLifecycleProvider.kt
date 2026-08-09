package io.github.kdh949.beanflow.payment.api

data class RegisterPaymentMethodProviderCommand(
    val authorizationKey: String,
    val providerCustomerReference: String,
)

sealed interface PaymentMethodRegistrationProviderResult {
    data class Issued(
        val tokenReference: String,
        val cardBrand: String,
        val lastFour: String,
    ) : PaymentMethodRegistrationProviderResult

    data object RejectedWithoutEffect : PaymentMethodRegistrationProviderResult

    data object Unknown : PaymentMethodRegistrationProviderResult

    data object Misconfigured : PaymentMethodRegistrationProviderResult
}

fun interface PaymentMethodRegistrationProvider {
    fun register(command: RegisterPaymentMethodProviderCommand): PaymentMethodRegistrationProviderResult
}

data class DeactivatePaymentMethodProviderCommand(
    val tokenReference: String,
    val providerCustomerReference: String,
)

sealed interface PaymentMethodDeactivationProviderResult {
    data object Deactivated : PaymentMethodDeactivationProviderResult

    data object RejectedWithoutEffect : PaymentMethodDeactivationProviderResult

    data object Unknown : PaymentMethodDeactivationProviderResult

    data object Misconfigured : PaymentMethodDeactivationProviderResult
}

fun interface PaymentMethodDeactivationProvider {
    fun deactivate(command: DeactivatePaymentMethodProviderCommand): PaymentMethodDeactivationProviderResult
}
