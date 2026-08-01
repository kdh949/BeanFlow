package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class RefundPointAccrualUnit(
    val orderLineId: UUID,
    val unitPosition: Int,
    val accruedAmountKrw: Long,
)

data class RefundPointAccrualSnapshotSource(
    val orderId: UUID,
    val orderState: String,
    val outcomeAt: Instant?,
    val outcomeSourceReference: String?,
    val aggregateVersion: Long?,
    val snapshotSchemaVersion: Int?,
    val snapshotHash: String?,
    val units: List<RefundPointAccrualUnit>,
)

data class PreparePointAccrualCompletionCommand(
    val orderId: UUID,
    val completedAt: Instant,
    val completionSourceReference: String,
    val aggregateVersion: Long,
    val snapshotSchemaVersion: Int,
    val snapshotHash: String,
    val units: List<RefundPointAccrualUnit>,
    val processedAt: Instant,
)

data class RefundPointUnitKey(
    val orderLineId: UUID,
    val unitPosition: Int,
)

data class PointAccrualCompletionEligibility(
    val excludedUnits: Set<RefundPointUnitKey>,
)

data class RecordPointAccrualNotApplicableCommand(
    val orderId: UUID,
    val orderState: String,
    val outcomeAt: Instant,
    val sourceReference: String,
    val aggregateVersion: Long,
)

data class ClaimedRefundPointRecovery(
    val workId: UUID,
    val refundId: UUID,
    val orderId: UUID,
    val claimToken: UUID,
    val attemptCount: Int,
    val needsEligibility: Boolean,
)

data class PreparedRefundPointRecovery(
    val refundId: UUID,
    val orderId: UUID,
    val refundSucceededAt: Instant,
    val refundSourceReference: String,
    val completedAt: Instant,
    val completionSourceReference: String,
    val snapshotSchemaVersion: Int,
    val snapshotHash: String,
    val targetAmountKrw: Long,
)

data class RefundPointRecoveryResult(
    val recoveredAmountKrw: Long,
    val pendingAmountKrw: Long,
)

interface RefundPointRecoveryOperations {
    fun prepareCompletion(command: PreparePointAccrualCompletionCommand): PointAccrualCompletionEligibility

    fun recordNotApplicable(command: RecordPointAccrualNotApplicableCommand)

    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedRefundPointRecovery>

    fun prepareRecovery(
        claim: ClaimedRefundPointRecovery,
        source: RefundPointAccrualSnapshotSource,
        now: Instant,
    ): PreparedRefundPointRecovery?

    fun recordSuccess(
        claim: ClaimedRefundPointRecovery,
        result: RefundPointRecoveryResult,
        now: Instant,
    )

    fun recordFailure(
        claim: ClaimedRefundPointRecovery,
        failure: RuntimeException,
        now: Instant,
    )
}
