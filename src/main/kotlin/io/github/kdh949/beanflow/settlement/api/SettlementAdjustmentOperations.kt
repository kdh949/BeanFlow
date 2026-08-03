package io.github.kdh949.beanflow.settlement.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class SettlementAdjustmentReasonCode {
    REFUND_SUCCEEDED,
    DISPUTE_ACCEPTED,
}

data class CreateSettlementAdjustmentCommand(
    val settlementItemId: UUID,
    val adjustmentSource: String,
    val reasonCode: SettlementAdjustmentReasonCode,
    val effectiveAt: Instant,
    val amountKrw: Long,
    val correlationId: String,
)

data class SettlementAdjustmentResult(
    val settlementAdjustmentId: UUID,
    val settlementItemId: UUID,
    val sourceSettlementBatchId: UUID,
    val storeId: UUID,
    val adjustmentSource: String,
    val reasonCode: SettlementAdjustmentReasonCode,
    val effectiveAt: Instant,
    val orderCompletedAt: Instant,
    val settlementDate: LocalDate,
    val currency: String,
    val amountKrw: Long,
)

interface SettlementAdjustmentOperations {
    fun create(command: CreateSettlementAdjustmentCommand): SettlementAdjustmentResult
}

data class ConfirmedSettlementItemView(
    val settlementItemId: UUID,
    val settlementBatchId: UUID,
    val storeId: UUID,
    val itemSource: String,
    val orderCompletedAt: Instant,
    val settlementDate: LocalDate,
    val currency: String,
    val batchConfirmedAt: Instant,
)

interface ConfirmedSettlementItemOperations {
    fun find(settlementItemId: UUID): ConfirmedSettlementItemView?
}
