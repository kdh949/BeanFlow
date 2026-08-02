package io.github.kdh949.beanflow.eventing.api

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RefundCompletionDisposition {
    COMPLETED_ORDER,
    PRE_COMPLETION_ORDER,
    PRE_ACCEPTANCE_CANCELLATION,
}

data class SettlementRefundEffect(
    val grossPaidDeltaKrw: Long,
    val feeDeltaKrw: Long,
    val benefitCostDeltaKrw: Long,
    val netSettlementDeltaKrw: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentRefundedV1(
    val envelope: EventEnvelope,
    val refundId: UUID,
    val refundSource: String,
    val orderId: UUID,
    val customerId: UUID,
    val refundSucceededAt: Instant,
    val currency: String,
    val cashRefundedKrw: Long,
    val completionDisposition: RefundCompletionDisposition,
    val orderCompletedAt: Instant? = null,
    val settlementDate: LocalDate? = null,
    val settlementItemSource: String? = null,
    val settlementRefundEffect: SettlementRefundEffect? = null,
)

data class PointsAccruedV1(
    val envelope: EventEnvelope,
    val pointTransactionSource: String,
    val orderCompletionSource: String,
    val orderId: UUID,
    val orderCompletedAt: Instant,
    val amountKrw: Long,
    val currency: String,
)

enum class PointRestorationDisposition {
    RESTORE,
    COMPENSATION,
    SKIPPED,
}

data class PointsRestoredV1(
    val envelope: EventEnvelope,
    val pointTransactionSource: String,
    val refundSource: String,
    val orderId: UUID,
    val refundSucceededAt: Instant,
    val orderCompletedAt: Instant?,
    val amountKrw: Long,
    val currency: String,
    val restorationDisposition: PointRestorationDisposition,
)

data class SettlementItemCreatedV1(
    val envelope: EventEnvelope,
    val settlementItemId: UUID,
    val settlementBatchId: UUID,
    val itemSource: String,
    val orderId: UUID,
    val storeId: UUID,
    val completedAt: Instant,
    val settlementDate: LocalDate,
    val currency: String,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
)

interface FinancialEventPublicationOperations {
    fun publish(event: PaymentRefundedV1)

    fun publish(event: PointsAccruedV1)

    fun publish(event: PointsRestoredV1)

    fun publish(event: SettlementItemCreatedV1)
}
