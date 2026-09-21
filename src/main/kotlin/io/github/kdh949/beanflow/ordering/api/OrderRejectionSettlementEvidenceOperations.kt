package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class OrderRejectionCause {
    STORE_REJECTION,
    ACCEPTANCE_TIMEOUT,
}

enum class OrderRejectionSourceActorType {
    STORE_OWNER,
    STORE_STAFF,
    SYSTEM_TIMEOUT,
}

data class OrderRejectionSettlementEvidence(
    val orderId: UUID,
    val customerId: UUID,
    val state: String,
    val aggregateVersion: Long,
    val rejectedAt: Instant?,
    val rejectionCause: OrderRejectionCause?,
    val rejectionActorType: OrderRejectionSourceActorType?,
    val rejectionEventId: UUID?,
    val rejectionTerminalVersion: Long?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
)

interface OrderRejectionSettlementEvidenceOperations {
    fun find(orderId: UUID): OrderRejectionSettlementEvidence?
}
