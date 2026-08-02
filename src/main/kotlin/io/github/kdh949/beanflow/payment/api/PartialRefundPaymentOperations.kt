package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

enum class PartialRefundAuditActorType {
    STORE_OWNER,
    STORE_STAFF,
    PLATFORM_OPERATOR,
}

enum class PartialRefundIssuerType {
    PLATFORM,
    BRAND,
    STORE,
}

enum class PartialRefundRestorationPolicyMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class PartialRefundStoredResponse(
    val status: Int,
    val body: String,
)

sealed interface PartialRefundPaymentLock {
    data class Replay(
        val refundId: UUID,
        val response: PartialRefundStoredResponse?,
        val inProgress: Boolean,
    ) : PartialRefundPaymentLock

    data class Ready(
        val paymentId: UUID,
        val orderId: UUID,
        val approvedAmountKrw: Long,
        val succeededRefundAmountKrw: Long,
        val consumedQuantityByOrderLine: Map<UUID, Long>,
        val consumedPointsByReservationAllocation: Map<UUID, Long>,
    ) : PartialRefundPaymentLock
}

data class LockPartialRefundPaymentCommand(
    val paymentId: UUID,
    val actorId: UUID,
    val idempotencyKey: String,
    val payloadHash: String,
)

data class PartialRefundLineRequestSnapshot(
    val orderLineId: UUID,
    val lineSequence: Int,
    val firstUnitIndex: Long,
    val quantity: Long,
    val originalQuantity: Long,
    val grossKrw: Long,
    val couponAttributionKrw: Long,
    val pointsRestorationKrw: Long,
    val cashRefundKrw: Long,
)

data class PartialRefundPointRequestSnapshot(
    val orderLineId: UUID,
    val pointReservationAllocationId: UUID,
    val originalPointLotId: UUID,
    val issuerType: PartialRefundIssuerType,
    val issuerReference: String,
    val requestedAmountKrw: Long,
)

data class PartialRefundSettlementContext(
    val customerId: UUID,
    val storeId: UUID,
    val orderState: String,
    val orderCompletedAt: Instant?,
    val orderAggregateVersion: Long,
    val grossPaidKrw: Long,
    val feeBaseKrw: Long,
    val feeRateBps: Int,
    val couponDiscountKrw: Long,
    val couponStoreShareBps: Int,
    val pointsAppliedKrw: Long,
    val pointCostKrw: Long,
    val currency: String,
)

data class CreatePartialRefundPaymentCommand(
    val paymentId: UUID,
    val orderId: UUID,
    val actorId: UUID,
    val auditActorType: PartialRefundAuditActorType,
    val idempotencyKey: String,
    val payloadHash: String,
    val reason: String,
    val policyVersionId: Long,
    val policyMode: PartialRefundRestorationPolicyMode,
    val compensationValidityDays: Int,
    val lineRequests: List<PartialRefundLineRequestSnapshot>,
    val pointRequests: List<PartialRefundPointRequestSnapshot>,
    val settlementContext: PartialRefundSettlementContext,
    val now: Instant,
)

data class PreparedPartialRefundPayment(
    val refundId: UUID,
    val cashAmountKrw: Long,
)

enum class PartialRefundProviderMode {
    REQUEST,
    LOOKUP,
}

data class ClaimedPartialRefundProvider(
    val refundId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val amountKrw: Long,
    val providerIdempotencyKey: String,
    val mode: PartialRefundProviderMode,
    val attemptCount: Int,
    val claimToken: UUID,
    val dueAt: Instant,
)

sealed interface PartialRefundProviderOutcome {
    data class Succeeded(
        val providerRefundReference: String,
    ) : PartialRefundProviderOutcome

    data class Failed(
        val code: String,
    ) : PartialRefundProviderOutcome

    data class RetryableFailed(
        val code: String,
    ) : PartialRefundProviderOutcome

    data class Unknown(
        val code: String,
    ) : PartialRefundProviderOutcome
}

data class PartialRefundProviderCompletion(
    val claim: ClaimedPartialRefundProvider,
    val outcome: PartialRefundProviderOutcome,
)

data class ClaimedPartialRefundRestoration(
    val workId: UUID,
    val refundId: UUID,
    val claimToken: UUID,
    val attemptCount: Int,
    val dueAt: Instant,
)

data class PartialRefundRestorationSlice(
    val orderLineId: UUID,
    val pointReservationAllocationId: UUID,
    val originalPointLotId: UUID,
    val issuerType: PartialRefundIssuerType,
    val issuerReference: String,
    val amountKrw: Long,
)

data class PartialRefundRestorationCommandSnapshot(
    val refundId: UUID,
    val orderId: UUID,
    val refundSucceededAt: Instant,
    val sourceReference: String,
    val refundSourceReference: String,
    val correlationId: String,
    val policyVersionId: Long,
    val policyMode: PartialRefundRestorationPolicyMode,
    val compensationValidityDays: Int,
    val slices: List<PartialRefundRestorationSlice>,
)

interface PartialRefundPaymentOperations {
    fun orderId(paymentId: UUID): UUID

    /** Must run after the owning Order row has been locked by the coordinating transaction. */
    fun lock(command: LockPartialRefundPaymentCommand): PartialRefundPaymentLock

    /** Persists the immutable request snapshots in the transaction that owns [lock]. */
    fun create(command: CreatePartialRefundPaymentCommand): PreparedPartialRefundPayment

    /** Claims the Refund in its own transaction. */
    fun claimProvider(
        refundId: UUID,
        now: Instant,
    ): ClaimedPartialRefundProvider

    /** Claims due partial Refunds in its own transaction. */
    fun claimDueProviders(
        now: Instant,
        limit: Int,
    ): List<ClaimedPartialRefundProvider>

    /** Calls the Provider without a database transaction. */
    fun callProvider(claim: ClaimedPartialRefundProvider): PartialRefundProviderCompletion

    /** Must run inside an Ordering-owned Order -> Payment -> Refund result transaction. */
    fun recordProviderCompletion(
        completion: PartialRefundProviderCompletion,
        settlementContext: PartialRefundSettlementContext,
        now: Instant,
    )

    fun recordOrReplayResponse(
        refundId: UUID,
        now: Instant,
    ): PartialRefundStoredResponse

    fun claimDueRestorations(
        now: Instant,
        limit: Int,
    ): List<ClaimedPartialRefundRestoration>

    fun restorationCommand(refundId: UUID): PartialRefundRestorationCommandSnapshot

    fun recordRestorationSuccess(
        claim: ClaimedPartialRefundRestoration,
        restoredAmountKrw: Long,
        now: Instant,
    )

    fun recordRestorationFailure(
        claim: ClaimedPartialRefundRestoration,
        failure: RuntimeException,
        now: Instant,
    )
}
