package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
internal class LocalPaymentGatewayConfiguration {
    @Bean
    fun localScriptedPaymentGateway(): PaymentGateway =
        object : PaymentGateway {
            override fun approve(request: GatewayApprovalRequest): ProviderPaymentResult =
                when {
                    request.tokenReference.endsWith(":approved") -> {
                        ProviderPaymentResult.Approved(
                            "sandbox-${request.paymentId}",
                            request.amountKrw,
                            request.currency,
                        )
                    }

                    request.tokenReference.endsWith(":declined") -> {
                        ProviderPaymentResult.Declined("SANDBOX_DECLINED")
                    }

                    else -> {
                        ProviderPaymentResult.Unknown("SANDBOX_UNKNOWN")
                    }
                }

            override fun confirmOneTime(request: GatewayOneTimeConfirmationRequest): ProviderPaymentResult =
                when {
                    request.paymentKey.endsWith(":approved") -> {
                        ProviderPaymentResult.Approved(
                            request.paymentKey,
                            request.amountKrw,
                            request.currency,
                        )
                    }

                    request.paymentKey.endsWith(":declined") -> {
                        ProviderPaymentResult.Declined("SCRIPTED_DECLINED")
                    }

                    else -> {
                        ProviderPaymentResult.Unknown("SCRIPTED_UNKNOWN")
                    }
                }

            override fun lookup(request: GatewayLookupRequest): ProviderPaymentResult =
                if (request.tokenReference?.endsWith(":eventually-approved") == true) {
                    ProviderPaymentResult.Approved(
                        request.providerTransactionReference ?: "sandbox-${request.paymentId}",
                        request.amountKrw,
                        request.currency,
                    )
                } else {
                    ProviderPaymentResult.Unknown("SANDBOX_UNKNOWN")
                }

            override fun void(
                request: GatewayLookupRequest,
                providerIdempotencyKey: String,
            ): GatewayRecoveryResult = GatewayRecoveryResult.Succeeded

            override fun refund(
                request: GatewayLookupRequest,
                amountKrw: Long,
                providerIdempotencyKey: String,
            ): GatewayRecoveryResult = GatewayRecoveryResult.Succeeded

            override fun requestRefund(
                request: GatewayLookupRequest,
                amountKrw: Long,
                providerIdempotencyKey: String,
            ): GatewayRefundResult = GatewayRefundResult.Succeeded("sandbox-refund-${request.paymentId}")

            override fun lookupRefund(
                request: GatewayLookupRequest,
                providerIdempotencyKey: String,
            ): GatewayRefundResult = GatewayRefundResult.Succeeded("sandbox-refund-${request.paymentId}")
        }
}
