package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.StoreRejectionRefundEvidence
import io.github.kdh949.beanflow.payment.api.StoreRejectionRefundEvidenceOperations
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreRejectionRefundEvidenceService(
    private val refunds: RefundJpaRepository,
) : StoreRejectionRefundEvidenceOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(refundId: UUID): StoreRejectionRefundEvidence? =
        refunds.findById(refundId).orElse(null)?.let { refund ->
            val succeeded = refund.state == RefundState.SUCCEEDED
            StoreRejectionRefundEvidence(
                refundId = refund.id,
                orderId = refund.orderId,
                aggregateVersion = refund.version,
                succeeded = succeeded,
                requestedAmountKrw = refund.requestedAmountKrw,
                succeededAmountKrw = refund.succeededAmountKrw,
                reason = refund.reason,
                sourceReference = refund.sourceReference,
                succeededAt = refund.updatedAt.takeIf { succeeded },
            )
        }
}
