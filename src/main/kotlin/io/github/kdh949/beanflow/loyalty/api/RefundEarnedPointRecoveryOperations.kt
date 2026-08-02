package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

data class RecoverRefundEarnedPointsCommand(
    val refundId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val refundSucceededAt: Instant,
    val refundSourceReference: String,
    val completedAt: Instant,
    val completionSourceReference: String,
    val completionAggregateVersion: Long,
    val snapshotSchemaVersion: Int,
    val snapshotHash: String,
    val targetAmountKrw: Long,
    val processedAt: Instant,
)

data class RecoverRefundEarnedPointsResult(
    val recoveredAmountKrw: Long,
    val pendingAmountKrw: Long,
    val replayed: Boolean,
)

data class AccrualUnitKey(
    val orderLineId: UUID,
    val unitPosition: Int,
)

data class AccrualUnitAmount(
    val orderLineId: UUID,
    val unitPosition: Int,
    val accruedAmountKrw: Long,
)

data class AccrueCompletedOrderPointsCommand(
    val orderId: UUID,
    val customerId: UUID,
    val completedAt: Instant,
    val completionSourceReference: String,
    val completionAggregateVersion: Long,
    val snapshotSchemaVersion: Int,
    val snapshotHash: String,
    val snapshotGrossAmountKrw: Long,
    val issuerType: PointIssuerType,
    val issuerReference: String,
    val expiresAt: Instant,
    val units: List<AccrualUnitAmount>,
    val excludedUnits: Set<AccrualUnitKey>,
    val correlationId: String,
    val processedAt: Instant,
)

data class AccrueCompletedOrderPointsResult(
    val snapshotGrossAmountKrw: Long,
    val excludedAmountKrw: Long,
    val accruedAmountKrw: Long,
    val offsetAmountKrw: Long,
    val availableAmountKrw: Long,
    val replayed: Boolean,
)

data class RecordLegacyCompletedOrderPointsCommand(
    val orderId: UUID,
    val completedAt: Instant,
    val completionSourceReference: String,
    val completionAggregateVersion: Long,
    val processedAt: Instant,
)

interface RefundEarnedPointRecoveryOperations {
    /** Recovers actual available lots and records only the residual as pending in one Loyalty transaction. */
    fun recover(command: RecoverRefundEarnedPointsCommand): RecoverRefundEarnedPointsResult

    /** Records gross accrual and offsets oldest pending recovery rows in the same Loyalty transaction. */
    fun accrue(command: AccrueCompletedOrderPointsCommand): AccrueCompletedOrderPointsResult

    /** Records an explicit rollout-era legacy marker without synthesizing a zero-valued snapshot. */
    fun recordLegacyNotApplicable(command: RecordLegacyCompletedOrderPointsCommand): Boolean
}
