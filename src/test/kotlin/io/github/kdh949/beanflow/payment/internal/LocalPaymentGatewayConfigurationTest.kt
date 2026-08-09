package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class LocalPaymentGatewayConfigurationTest {
    private val gateway = LocalPaymentGatewayConfiguration().localScriptedPaymentGateway()
    private val lookup =
        GatewayLookupRequest(
            paymentId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
            provider = "TOSS",
            tokenReference = "pay:approved",
            providerCustomerReference = null,
            providerTransactionReference = "pay:approved",
            amountKrw = 10_000,
            currency = "KRW",
        )

    @Test
    fun `refund references replay by Provider idempotency key and differ between refund commands`() {
        val first = gateway.requestRefund(lookup, 5_000, "refund-key-one")
        val replay = gateway.lookupRefund(lookup, "refund-key-one")
        val second = gateway.requestRefund(lookup, 5_000, "refund-key-two")

        assertThat(first).isEqualTo(replay)
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `one-time payment lookup uses the retained Provider transaction reference`() {
        val oneTime =
            lookup.copy(
                tokenReference = null,
                providerTransactionReference = "one-time:eventually-approved",
                providerOrderId = "bf_order_123",
            )

        assertThat(gateway.lookup(oneTime))
            .isEqualTo(
                ProviderPaymentResult.Approved(
                    "one-time:eventually-approved",
                    oneTime.amountKrw,
                    oneTime.currency,
                ),
            )
    }
}
