package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

data class PartialRefundPointSourceAllocation(
    val pointReservationAllocationId: UUID,
    val pointLotId: UUID,
    val amountKrw: Long,
    val expiresAt: Instant,
    val issuerType: PointIssuerType,
    val issuerReference: String,
)

data class PartialRefundPointSourceSnapshot(
    val pointReservationId: UUID?,
    val allocations: List<PartialRefundPointSourceAllocation>,
)

data class PartialRefundPointSlice(
    val orderLineId: UUID,
    val pointReservationAllocationId: UUID,
    val originalPointLotId: UUID,
    val issuerType: PointIssuerType,
    val issuerReference: String,
    val amountKrw: Long,
)

enum class PartialRefundPointPolicyMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class RestorePartialRefundPointsCommand(
    val refundId: UUID,
    val orderId: UUID,
    val refundSucceededAt: Instant,
    val sourceReference: String,
    val refundSourceReference: String,
    val orderCompletedAt: Instant?,
    val correlationId: String,
    val policyVersionId: Long,
    val policyMode: PartialRefundPointPolicyMode,
    val compensationValidityDays: Int,
    val slices: List<PartialRefundPointSlice>,
)

data class PartialRefundPointRestorationResult(
    val restoredAmountKrw: Long,
    val replayed: Boolean,
)

interface PartialRefundPointOperations {
    /** Reads and locks the USED reservation and original allocations for a Refund request snapshot. */
    fun lockSourceSnapshot(orderId: UUID): PartialRefundPointSourceSnapshot

    /** Runs in Loyalty's own local transaction and is idempotent for the exact Refund source/payload. */
    fun restore(command: RestorePartialRefundPointsCommand): PartialRefundPointRestorationResult
}
