package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class PrepareExternalPaymentCommand(
	val actorId: UUID,
	val orderId: UUID,
	val paymentMethodId: UUID,
	val requestedAmountKrw: Long,
	val idempotencyKey: String,
	val payloadHash: String,
	val correlationId: String,
	val now: Instant,
)

enum class PaymentPreparationState {
	ACQUIRED,
	IN_PROGRESS,
	CURRENT,
}

data class PaymentPreparation(
	val paymentId: UUID,
	val state: PaymentPreparationState,
	val current: ExternalPaymentView? = null,
	val responseStatus: Int? = null,
	val responseBody: String? = null,
)

sealed interface ProviderPaymentResult {
	data class Approved(
		val providerTransactionReference: String,
		val amountKrw: Long,
		val currency: String,
	) : ProviderPaymentResult

	data class Declined(val code: String) : ProviderPaymentResult
	data class Unknown(val code: String) : ProviderPaymentResult
}

class ProviderTransportFailure(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

data class ApplyExternalPaymentResultCommand(
	val paymentId: UUID,
	val result: ProviderPaymentResult,
	val responseStatus: Int,
	val responseBody: String,
	val now: Instant,
	val lateApproval: Boolean = false,
)

data class ExternalPaymentView(
	val paymentId: UUID,
	val orderId: UUID,
	val type: String,
	val approvalState: String,
	val approvedAmountKrw: Long?,
	val currency: String,
	val recoveryState: String,
	val updatedAt: Instant,
	val correlationId: String,
)

interface ExternalPaymentOperations {
	fun existing(command: PrepareExternalPaymentCommand): PaymentPreparation?
	fun prepare(command: PrepareExternalPaymentCommand): PaymentPreparation
	fun requestProviderApproval(paymentId: UUID): ProviderPaymentResult
	fun applyResult(command: ApplyExternalPaymentResultCommand): ExternalPaymentView
	fun current(paymentId: UUID): ExternalPaymentView
}
