package io.github.kdh949.beanflow.payment.internal.domain

import java.time.Instant
import java.util.UUID

enum class PaymentType {
	EXTERNAL,
	BENEFIT_ONLY,
}

enum class PaymentApprovalState {
	READY,
	APPROVING,
	APPROVED,
	FAILED,
	UNKNOWN,
	RECONCILING,
	MANUAL_REVIEW,
}

sealed interface ProviderApproval {
	data class Approved(
		val providerTransactionReference: String,
		val amountKrw: Long,
		val currency: String,
	) : ProviderApproval

	data class Declined(val code: String) : ProviderApproval
	data class Unknown(val code: String) : ProviderApproval
}

class Payment private constructor(
	val id: UUID,
	val orderId: UUID,
	val customerId: UUID,
	val paymentMethodId: UUID?,
	val type: PaymentType,
	val requestedAmountKrw: Long,
	val currency: String,
	val correlationId: String,
	approvalState: PaymentApprovalState,
	approvedAmountKrw: Long?,
	providerTransactionReference: String?,
	val createdAt: Instant,
	updatedAt: Instant,
) {
	var approvalState: PaymentApprovalState = approvalState
		private set
	var approvedAmountKrw: Long? = approvedAmountKrw
		private set
	var providerTransactionReference: String? = providerTransactionReference
		private set
	var updatedAt: Instant = updatedAt
		private set

	fun apply(result: ProviderApproval, now: Instant) {
		check(
			approvalState == PaymentApprovalState.APPROVING ||
				approvalState == PaymentApprovalState.UNKNOWN ||
				approvalState == PaymentApprovalState.RECONCILING,
		) { "Payment result cannot be applied from $approvalState" }
		when (result) {
			is ProviderApproval.Approved -> {
				providerTransactionReference = result.providerTransactionReference
				.takeIf(String::isNotBlank)
					?: throw IllegalArgumentException("Provider transaction reference is required")
				if (result.amountKrw == requestedAmountKrw && result.currency == currency) {
					approvedAmountKrw = result.amountKrw
					approvalState = PaymentApprovalState.APPROVED
				} else {
					approvedAmountKrw = null
					approvalState = PaymentApprovalState.RECONCILING
				}
			}
			is ProviderApproval.Declined -> {
				require(result.code.isNotBlank())
				approvalState = PaymentApprovalState.FAILED
			}
			is ProviderApproval.Unknown -> {
				require(result.code.isNotBlank())
				approvalState = PaymentApprovalState.UNKNOWN
			}
		}
		updatedAt = now
	}

	fun startReconciliation(now: Instant) {
		check(
			approvalState == PaymentApprovalState.UNKNOWN ||
				approvalState == PaymentApprovalState.APPROVING ||
				approvalState == PaymentApprovalState.RECONCILING,
		)
		approvalState = PaymentApprovalState.RECONCILING
		updatedAt = now
	}

	fun markLateApproval(result: ProviderApproval.Approved, now: Instant) {
		check(
			approvalState == PaymentApprovalState.APPROVING ||
				approvalState == PaymentApprovalState.UNKNOWN ||
				approvalState == PaymentApprovalState.RECONCILING,
		) { "Late approval cannot be applied from $approvalState" }
		require(result.providerTransactionReference.isNotBlank())
		providerTransactionReference = result.providerTransactionReference
		approvedAmountKrw = result.amountKrw
		approvalState = PaymentApprovalState.RECONCILING
		updatedAt = now
	}

	fun requireManualReview(now: Instant) {
		check(
			approvalState == PaymentApprovalState.UNKNOWN ||
				approvalState == PaymentApprovalState.RECONCILING ||
				approvalState == PaymentApprovalState.APPROVING,
		)
		approvalState = PaymentApprovalState.MANUAL_REVIEW
		updatedAt = now
	}

	companion object {
		fun restore(
			id: UUID,
			orderId: UUID,
			customerId: UUID,
			paymentMethodId: UUID?,
			type: PaymentType,
			requestedAmountKrw: Long,
			currency: String,
			correlationId: String,
			approvalState: PaymentApprovalState,
			approvedAmountKrw: Long?,
			providerTransactionReference: String?,
			createdAt: Instant,
			updatedAt: Instant,
		): Payment = Payment(
			id = id,
			orderId = orderId,
			customerId = customerId,
			paymentMethodId = paymentMethodId,
			type = type,
			requestedAmountKrw = requestedAmountKrw,
			currency = currency,
			correlationId = correlationId,
			approvalState = approvalState,
			approvedAmountKrw = approvedAmountKrw,
			providerTransactionReference = providerTransactionReference,
			createdAt = createdAt,
			updatedAt = updatedAt,
		)

		fun externalApproving(
			id: UUID,
			orderId: UUID,
			customerId: UUID,
			paymentMethodId: UUID,
			requestedAmountKrw: Long,
			correlationId: String,
			now: Instant,
		): Payment {
			require(requestedAmountKrw > 0) { "External payment amount must be positive" }
			require(correlationId.isNotBlank()) { "Correlation ID is required" }
			return Payment(
				id = id,
				orderId = orderId,
				customerId = customerId,
				paymentMethodId = paymentMethodId,
				type = PaymentType.EXTERNAL,
				requestedAmountKrw = requestedAmountKrw,
				currency = "KRW",
				correlationId = correlationId,
				approvalState = PaymentApprovalState.APPROVING,
				approvedAmountKrw = null,
				providerTransactionReference = null,
				createdAt = now,
				updatedAt = now,
			)
		}
	}
}
