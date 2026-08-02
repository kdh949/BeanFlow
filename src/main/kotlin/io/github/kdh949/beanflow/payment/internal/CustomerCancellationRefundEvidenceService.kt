package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.CustomerCancellationRefundEvidence
import io.github.kdh949.beanflow.payment.api.CustomerCancellationRefundEvidenceOperations
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerCancellationRefundEvidenceService(
    private val refunds: RefundJpaRepository,
) : CustomerCancellationRefundEvidenceOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun find(refundId: UUID): CustomerCancellationRefundEvidence? =
        refunds.findById(refundId).orElse(null)?.let { refund ->
            val succeeded = refund.state == RefundState.SUCCEEDED
            CustomerCancellationRefundEvidence(
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
