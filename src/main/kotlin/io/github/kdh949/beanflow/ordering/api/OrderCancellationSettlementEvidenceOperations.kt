package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class OrderCancellationCause {
    CUSTOMER_REQUEST,
    PAYMENT_DECLINED,
}

enum class CustomerCancellationReasonCode {
    CHANGED_MIND,
    ORDER_MISTAKE,
    WAIT_TOO_LONG,
    PICKUP_TIME_CONFLICT,
    PAYMENT_ISSUE,
    OTHER,
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
