package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ApprovedPaymentSettlement
import io.github.kdh949.beanflow.payment.api.ApprovedPaymentSettlementOperations
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class ApprovedPaymentSettlementService(
    private val repository: PaymentJpaRepository,
) : ApprovedPaymentSettlementOperations {
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun readForCompletion(orderId: UUID): ApprovedPaymentSettlement {
        val payment =
            repository.findByOrderId(orderId)
                ?: unavailable("Completed Order approved Payment source is missing")
        val approvedAmountKrw = payment.approvedAmountKrw
        val approvedAt = payment.approvedAt
        if (payment.approvalState != PaymentApprovalState.APPROVED ||
            approvedAmountKrw == null || approvedAmountKrw < 0 || approvedAt == null ||
            payment.currency != "KRW" || payment.sourceReference.isBlank()
        ) {
            unavailable("Completed Order Payment source is not an exact approved settlement fact")
        }
        return ApprovedPaymentSettlement(
            orderId = payment.orderId,
            approvedAmountKrw = approvedAmountKrw,
            currency = payment.currency,
            approvedAt = approvedAt,
            approvalSource = payment.sourceReference,
        )
    }

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message)
}
