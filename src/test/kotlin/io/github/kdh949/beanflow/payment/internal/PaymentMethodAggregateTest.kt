package io.github.kdh949.beanflow.payment.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class PaymentMethodAggregateTest {
    private val now = Instant.parse("2026-08-09T02:00:00Z")

    @Test
    fun `issued Toss method validates the public display boundary`() {
        val method = method()

        assertThat(method.provider).isEqualTo("TOSS_PAYMENTS")
        assertThat(method.status).isEqualTo(PaymentMethodStatus.ACTIVE)
        assertThat(method.isDefault).isFalse()

        assertThatThrownBy { method(displayAlias = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { method(displayAlias = "x\n") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { method(cardBrand = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { method(lastFour = "42x2") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { method(providerReference = "customer-email") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `default is allowed only for active method and deactivation clears it monotonically`() {
        val method = method()
        method.markDefault(now)
        assertThat(method.isDefault).isTrue()

        method.requestDeactivation(now.plusSeconds(1))
        assertThat(method.status).isEqualTo(PaymentMethodStatus.DEACTIVATION_REQUESTED)
        assertThat(method.isDefault).isFalse()

        assertThatThrownBy { method.markDefault(now.plusSeconds(2)) }
            .isInstanceOf(PaymentMethodStateConflict::class.java)
        assertThatThrownBy { method.requestDeactivation(now.plusSeconds(2)) }
            .isInstanceOf(PaymentMethodStateConflict::class.java)
    }

    @Test
    fun `unknown deactivation converges only forward to manual review or deactivated`() {
        val manual = method().also { it.requestDeactivation(now) }
        manual.markDeactivationUnknown(now.plusSeconds(1))
        manual.markManualReview(now.plusSeconds(2))
        assertThat(manual.status).isEqualTo(PaymentMethodStatus.MANUAL_REVIEW)

        val confirmed = method().also { it.requestDeactivation(now) }
        confirmed.markDeactivationUnknown(now.plusSeconds(1))
        confirmed.confirmDeactivated(now.plusSeconds(2))
        assertThat(confirmed.status).isEqualTo(PaymentMethodStatus.DEACTIVATED)

        assertThatThrownBy { confirmed.markDeactivationUnknown(now.plusSeconds(3)) }
            .isInstanceOf(PaymentMethodStateConflict::class.java)
    }

    private fun method(
        displayAlias: String = "Main card",
        cardBrand: String = "VISA",
        lastFour: String = "4242",
        providerReference: String = "bf_${"a".repeat(43)}",
    ): PaymentMethodEntity =
        PaymentMethodEntity.issueToss(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            tokenReference = "opaque-token",
            providerCustomerReference = providerReference,
            displayAlias = displayAlias,
            cardBrand = cardBrand,
            lastFour = lastFour,
            now = now,
        )
}
