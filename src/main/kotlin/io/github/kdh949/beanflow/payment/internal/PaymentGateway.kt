package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import java.util.UUID

internal data class GatewayApprovalRequest(
    val paymentId: UUID,
    val provider: String,
    val tokenReference: String,
    val amountKrw: Long,
    val currency: String,
    val providerIdempotencyKey: String,
)

internal data class GatewayLookupRequest(
    val paymentId: UUID,
    val provider: String,
    val tokenReference: String,
    val providerTransactionReference: String?,
    val amountKrw: Long,
    val currency: String,
)

internal sealed interface GatewayRecoveryResult {
    data object Succeeded : GatewayRecoveryResult

    data object Unavailable : GatewayRecoveryResult

    data class Unknown(
        val code: String,
    ) : GatewayRecoveryResult
}

internal sealed interface GatewayRefundResult {
    data class Succeeded(
        val providerRefundReference: String,
    ) : GatewayRefundResult

    data class Failed(
        val code: String,
    ) : GatewayRefundResult

    /** Adapter allowlisted a no-side-effect failure and same-key re-request as safe. */
    data class RetryableFailed(
        val code: String,
    ) : GatewayRefundResult

    data class Unknown(
        val code: String,
    ) : GatewayRefundResult
}

internal interface PaymentGateway {
    fun approve(request: GatewayApprovalRequest): ProviderPaymentResult

    fun lookup(request: GatewayLookupRequest): ProviderPaymentResult

    fun void(
        request: GatewayLookupRequest,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult

    fun refund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult

    fun requestRefund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRefundResult

    fun lookupRefund(
        request: GatewayLookupRequest,
        providerIdempotencyKey: String,
    ): GatewayRefundResult
}
