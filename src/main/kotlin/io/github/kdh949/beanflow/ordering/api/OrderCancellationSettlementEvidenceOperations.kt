package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class OrderCancellationCause {
    CUSTOMER_REQUEST,
    PAYMENT_DECLINED,
}

data class OrderCancellationSettlementEvidence(
    val orderId: UUID,
    val customerId: UUID,
    val state: String,
    val aggregateVersion: Long,
    val cancelledAt: Instant?,
    val cancellationCause: OrderCancellationCause?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
)

interface OrderCancellationSettlementEvidenceOperations {
    fun find(orderId: UUID): OrderCancellationSettlementEvidence?
}
