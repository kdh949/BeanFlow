package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ApproveBenefitOnlyPaymentCommand
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentOperations
import io.github.kdh949.beanflow.payment.api.BenefitOnlyPaymentResult
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
internal class BenefitOnlyPaymentService(
	private val repository: PaymentJpaRepository,
) : BenefitOnlyPaymentOperations {

	@Transactional(propagation = Propagation.MANDATORY)
	override fun approve(command: ApproveBenefitOnlyPaymentCommand): BenefitOnlyPaymentResult {
		validate(command)
		repository.findBySourceReference(command.sourceReference)?.let { existing ->
			if (existing.orderId == command.orderId &&
				existing.benefitSnapshotReference == command.benefitSnapshotReference
			) {
				return existing.toResult()
			}
			fail(FailureCode.ORDER_STATE_CONFLICT, "Payment source reference was reused")
		}
		repository.findByOrderId(command.orderId)?.let {
			fail(FailureCode.ORDER_STATE_CONFLICT, "Order already has a payment")
		}
		val payment = PaymentEntity(
			id = command.paymentId,
			orderId = command.orderId,
			type = PaymentType.BENEFIT_ONLY,
			approvalState = PaymentApprovalState.APPROVED,
			approvedAmountKrw = command.approvedAmountKrw,
			currency = command.currency,
			benefitSnapshotReference = command.benefitSnapshotReference,
			sourceReference = command.sourceReference,
			correlationId = command.correlationId,
			approvedAt = command.approvedAt,
			updatedAt = command.approvedAt,
		)
		return repository.save(payment).toResult()
	}

	private fun validate(command: ApproveBenefitOnlyPaymentCommand) {
		if (command.approvedAmountKrw != 0L || command.currency != "KRW") {
			fail(FailureCode.INVALID_REQUEST, "BENEFIT_ONLY payment must approve zero KRW")
		}
		if (command.benefitSnapshotReference.isBlank() ||
			command.sourceReference.isBlank() ||
			command.correlationId.isBlank()
		) {
			fail(FailureCode.INVALID_REQUEST, "BENEFIT_ONLY payment references are required")
		}
	}

	private fun PaymentEntity.toResult() = BenefitOnlyPaymentResult(
		paymentId = id,
		orderId = orderId,
		type = type.name,
		approvalState = approvalState.name,
		approvedAmountKrw = approvedAmountKrw,
		currency = currency,
		updatedAt = updatedAt,
		correlationId = correlationId,
	)

	private fun fail(code: FailureCode, message: String): Nothing =
		throw DomainFailure(code, message)
}
