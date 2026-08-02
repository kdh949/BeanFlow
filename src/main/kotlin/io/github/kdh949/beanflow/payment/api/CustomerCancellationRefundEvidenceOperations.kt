package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class CustomerCancellationRefundEvidence(
    val refundId: UUID,
    val orderId: UUID,
    val aggregateVersion: Long,
    val succeeded: Boolean,
    val requestedAmountKrw: Long,
    val succeededAmountKrw: Long?,
    val reason: String,
    val sourceReference: String,
    val succeededAt: Instant?,
)

interface CustomerCancellationRefundEvidenceOperations {
    fun find(refundId: UUID): CustomerCancellationRefundEvidence?
}
