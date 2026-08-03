package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

data class PointAdjustmentIssuer(
    val issuerType: PointIssuerType,
    val issuerReference: String,
)

data class ApplyPointAdjustmentCommand(
    val actorId: UUID,
    val pointAccountId: UUID,
    val idempotencyKey: String,
    val amountKrw: Long,
    val issuer: PointAdjustmentIssuer?,
    val expiresAt: Instant?,
    val reason: String,
    val evidenceReferences: List<String>,
    val correlationId: String,
    val now: Instant,
)

data class PointAccountView(
    val accountId: UUID,
    val availablePointsKrw: Long,
    val recoveryPendingKrw: Long,
    val currency: String = "KRW",
)

enum class PointTransactionViewType {
    ACCRUAL,
    USE,
    EXPIRATION,
    RESTORE,
    COMPENSATION,
    RESTORE_SKIPPED_EXPIRED,
    RECOVERY,
    ADJUSTMENT,
}

data class PointTransactionView(
    val transactionId: UUID,
    val type: PointTransactionViewType,
    val amountKrw: Long,
    val occurredAt: Instant,
    val sourceReference: String,
)

data class PointAdjustmentResult(
    val account: PointAccountView,
    val transactions: List<PointTransactionView>,
)

interface PointAdjustmentOperations {
    fun adjust(command: ApplyPointAdjustmentCommand): PointAdjustmentResult
}
