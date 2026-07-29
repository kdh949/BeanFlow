package io.github.kdh949.beanflow.payment

import io.github.kdh949.beanflow.payment.internal.domain.Payment
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.ProviderApproval
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class PaymentStateTest {

	@Test
	fun `exact provider approval completes an external payment`() {
		val payment = payment()

		payment.apply(ProviderApproval.Approved("provider-transaction", 7_000, "KRW"), NOW)

		assertThat(payment.approvalState).isEqualTo(PaymentApprovalState.APPROVED)
		assertThat(payment.approvedAmountKrw).isEqualTo(7_000)
		assertThat(payment.providerTransactionReference).isEqualTo("provider-transaction")
	}

	@Test
	fun `amount mismatch is reconciling and never approved`() {
		val payment = payment()

		payment.apply(ProviderApproval.Approved("provider-transaction", 6_999, "KRW"), NOW)

		assertThat(payment.approvalState).isEqualTo(PaymentApprovalState.RECONCILING)
		assertThat(payment.approvedAmountKrw).isNull()
	}

	@Test
	fun `timeout is unknown rather than failed`() {
		val payment = payment()

		payment.apply(ProviderApproval.Unknown("TIMEOUT"), NOW)

		assertThat(payment.approvalState).isEqualTo(PaymentApprovalState.UNKNOWN)
	}

	@Test
	fun `explicit provider decline is a terminal failed payment`() {
		val payment = payment()

		payment.apply(ProviderApproval.Declined("DECLINED"), NOW)

		assertThat(payment.approvalState).isEqualTo(PaymentApprovalState.FAILED)
	}

	@Test
	fun `approved payment cannot be applied again`() {
		val payment = payment()
		payment.apply(ProviderApproval.Approved("provider-transaction", 7_000, "KRW"), NOW)

		assertThatThrownBy {
			payment.apply(ProviderApproval.Approved("another", 7_000, "KRW"), NOW)
		}.isInstanceOf(IllegalStateException::class.java)
	}

	private fun payment() = Payment.externalApproving(
		id = UUID.randomUUID(),
		orderId = UUID.randomUUID(),
		customerId = UUID.randomUUID(),
		paymentMethodId = UUID.randomUUID(),
		requestedAmountKrw = 7_000,
		correlationId = "correlation",
		now = NOW,
	)

	private companion object {
		val NOW: Instant = Instant.parse("2026-07-29T00:00:00Z")
	}
}
