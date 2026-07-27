package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class ApproveBenefitOnlyPaymentCommand(
	val paymentId: UUID,
	val orderId: UUID,
	val approvedAmountKrw: Long,
	val currency: String,
	val benefitSnapshotReference: String,
	val sourceReference: String,
	val correlationId: String,
	val approvedAt: Instant,
)

data class BenefitOnlyPaymentResult(
	val paymentId: UUID,
	val orderId: UUID,
	val type: String,
	val approvalState: String,
	val approvedAmountKrw: Long,
	val currency: String,
	val updatedAt: Instant,
	val correlationId: String,
)

interface BenefitOnlyPaymentOperations {
	fun approve(command: ApproveBenefitOnlyPaymentCommand): BenefitOnlyPaymentResult
}
