package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class ApprovedPaymentSettlement(
    val orderId: UUID,
    val approvedAmountKrw: Long,
    val currency: String,
    val approvedAt: Instant,
    val approvalSource: String,
)

interface ApprovedPaymentSettlementOperations {
    fun readForCompletion(orderId: UUID): ApprovedPaymentSettlement
}
