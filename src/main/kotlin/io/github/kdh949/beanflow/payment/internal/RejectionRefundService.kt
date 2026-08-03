package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.payment.api.PartialRefundSettlementContext
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.Refund
import io.github.kdh949.beanflow.payment.internal.domain.RefundClaimMode
import io.github.kdh949.beanflow.payment.internal.domain.RefundState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ClaimedRefund(
    val refundId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val reason: String,
    val amountKrw: Long,
    val providerIdempotencyKey: String,
    val mode: RefundClaimMode,
    val attemptCount: Int,
    val claimToken: UUID,
    val dueAt: Instant,
)

@Service
internal class RejectionRefundService(
    private val refundRepository: RefundJpaRepository,
    private val paymentRepository: PaymentJpaRepository,
    private val cancellationSnapshots: CustomerCancellationPaymentSnapshotJpaRepository,
    private val requestLoader: PaymentProviderRequestLoader,
    private val paymentGateway: PaymentGateway,
    private val compensationOperations: OrderCompensationOperations,
    private val partialRefundSuccessLedger: PartialRefundSuccessLedger,
    private val refundEventProducer: PaymentRefundEventProducer,
    private val identifierSource: IdentifierSource,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.refund.claim-lease:PT1M}")
    private val claimLease: Duration,
) {
    @Transactional
    fun request(event: OrderRejectedV1) {
        if (!event.paymentRequired) return
        val sourceReference = sourceReference(event)
        val existing = refundRepository.findBySourceReference(sourceReference)
        if (existing != null) {
            if (existing.state == RefundState.SUCCEEDED) {
                recordStep(event.orderId, OrderCompensationStepState.SUCCEEDED, null, event.rejectedAt)
            }
            return
        }
        val payment =
            paymentRepository.findByOrderId(event.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment for rejected order is missing")
        if (payment.type != PaymentType.EXTERNAL ||
            payment.approvalState != PaymentApprovalState.APPROVED
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Rejected order payment is not refundable")
        }
        val approvedAmount =
            payment.approvedAmountKrw
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved payment amount is missing")
        if (approvedAmount <= 0) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Rejected order payment amount is not refundable")
        }
        if (payment.succeededRefundAmountKrw != 0L) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Rejected order payment was already partially refunded")
        }
        refundRepository.findByPaymentIdAndReason(payment.id, REASON)?.let { existingRefund ->
            if (existingRefund.state == RefundState.SUCCEEDED) {
                recordStep(event.orderId, OrderCompensationStepState.SUCCEEDED, null, event.rejectedAt)
            }
            return
        }
        refundRepository.save(
            Refund
                .request(
                    id = identifierSource.next(),
                    paymentId = payment.id,
                    orderId = event.orderId,
                    requestedAmountKrw = approvedAmount,
                    reason = REASON,
                    providerIdempotencyKey = "refund:rejection:${event.envelope.eventId}",
                    sourceReference = sourceReference,
                    now = event.rejectedAt,
                ).toEntity(),
        )
    }

    @Transactional
    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedRefund> {
        require(limit in 1..100)
        return refundRepository
            .findDueIdsExcludingReason(now, PARTIAL_REFUND, PageRequest.of(0, limit))
            .mapNotNull { refundId ->
                claimLocked(refundId, now)
            }
    }

    @Transactional
    fun claimPartialDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedRefund> {
        require(limit in 1..100)
        return refundRepository.findDueIdsByReason(now, PARTIAL_REFUND, PageRequest.of(0, limit)).mapNotNull { refundId ->
            claimLocked(refundId, now)
        }
    }

    private fun claimLocked(
        refundId: UUID,
        now: Instant,
    ): ClaimedRefund? {
        val candidate = refundRepository.findById(refundId).orElse(null) ?: return null
        val payment =
            paymentRepository.findLockedById(candidate.paymentId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment for refund is missing")
        val entity = refundRepository.findLockedById(refundId) ?: return null
        val refund = entity.toDomain()
        val token = identifierSource.next()
        val dueAt = entity.nextAttemptAt ?: entity.claimUntil ?: now
        val mode =
            try {
                refund.claim(token, now, claimLease)
            } catch (_: IllegalStateException) {
                if (entity.state == RefundState.PROCESSING || entity.state == RefundState.RECONCILING) {
                    refund.recoverExpiredClaim(now)
                    entity.apply(refund)
                    if (entity.reason in COMPENSATION_REASONS) {
                        recordStep(
                            entity.orderId,
                            if (refund.state == RefundState.MANUAL_REVIEW) {
                                OrderCompensationStepState.MANUAL_REVIEW
                            } else {
                                OrderCompensationStepState.UNKNOWN
                            },
                            "CLAIM_LEASE_EXPIRED",
                            now,
                        )
                    }
                    if (entity.reason == CUSTOMER_CANCELLATION_REASON && refund.state == RefundState.MANUAL_REVIEW) {
                        publishCustomerCancellationDelayed(entity, payment, now)
                    }
                }
                return null
            }
        entity.apply(refund)
        return ClaimedRefund(
            refundId = entity.id,
            paymentId = entity.paymentId,
            orderId = entity.orderId,
            reason = entity.reason,
            amountKrw = entity.requestedAmountKrw,
            providerIdempotencyKey = entity.providerIdempotencyKey,
            mode = mode,
            attemptCount = entity.attemptCount,
            claimToken = token,
            dueAt = dueAt,
        )
    }

    @Transactional
    fun claimOne(
        refundId: UUID,
        now: Instant,
    ): ClaimedRefund {
        val entity =
            refundRepository.findLockedById(refundId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Refund is missing")
        if (entity.reason != PARTIAL_REFUND) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Refund is not a partial Refund")
        }
        val refund = entity.toDomain()
        val token = identifierSource.next()
        val dueAt = entity.nextAttemptAt ?: entity.claimUntil ?: now
        val mode =
            try {
                refund.claim(token, now, claimLease)
            } catch (failure: IllegalStateException) {
                throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, failure.message ?: "Refund is not claimable")
            }
        entity.apply(refund)
        return ClaimedRefund(
            refundId = entity.id,
            paymentId = entity.paymentId,
            orderId = entity.orderId,
            reason = entity.reason,
            amountKrw = entity.requestedAmountKrw,
            providerIdempotencyKey = entity.providerIdempotencyKey,
            mode = mode,
            attemptCount = entity.attemptCount,
            claimToken = token,
            dueAt = dueAt,
        )
    }

    fun callProvider(claim: ClaimedRefund): GatewayRefundResult {
        val request = requestLoader.loadLookup(claim.paymentId)
        return when (claim.mode) {
            RefundClaimMode.REQUEST -> {
                paymentGateway.requestRefund(
                    request,
                    claim.amountKrw,
                    claim.providerIdempotencyKey,
                )
            }

            RefundClaimMode.LOOKUP -> {
                paymentGateway.lookupRefund(
                    request,
                    claim.providerIdempotencyKey,
                )
            }
        }
    }

    @Transactional
    fun recordResult(
        claim: ClaimedRefund,
        result: GatewayRefundResult,
        now: Instant,
    ) {
        recordResult(claim, result, null, now)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun recordPartialResult(
        claim: ClaimedRefund,
        result: GatewayRefundResult,
        settlementContext: PartialRefundSettlementContext,
        now: Instant,
    ) {
        if (claim.reason != PARTIAL_REFUND) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Claim is not a partial Refund")
        }
        recordResult(claim, result, settlementContext, now)
    }

    private fun recordResult(
        claim: ClaimedRefund,
        result: GatewayRefundResult,
        settlementContext: PartialRefundSettlementContext?,
        now: Instant,
    ) {
        // Result transactions that need no Order lock always start at Payment, then lock Refund.
        // This preserves the repository-wide Order -> Payment -> Refund/allocation order.
        val payment =
            paymentRepository.findLockedById(claim.paymentId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment for refund is missing")
        val entity =
            refundRepository.findLockedById(claim.refundId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Claimed refund is missing")
        val refund = entity.toDomain()
        try {
            refund.requireClaim(claim.claimToken)
        } catch (failure: IllegalStateException) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, failure.message ?: "Refund claim was lost")
        }
        when (result) {
            is GatewayRefundResult.Succeeded -> {
                val approvedAmount =
                    payment.approvedAmountKrw
                        ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved payment amount is missing")
                val nextRefundedAmount = Math.addExact(payment.succeededRefundAmountKrw, refund.requestedAmountKrw)
                if (nextRefundedAmount > approvedAmount) {
                    fail(FailureCode.ORDER_STATE_CONFLICT, "Successful refunds would exceed approved amount")
                }
                refund.succeed(result.providerRefundReference, now)
                payment.succeededRefundAmountKrw = nextRefundedAmount
                payment.updatedAt = now
                entity.apply(refund)
                if (entity.reason == PARTIAL_REFUND) {
                    val context =
                        settlementContext
                            ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Partial Refund settlement context is missing")
                    partialRefundSuccessLedger.record(entity, payment, now)
                    refundEventProducer.publishPartial(entity, payment, context, now)
                } else {
                    refundEventProducer.publishPreAcceptance(entity, payment, now)
                    if (entity.reason == CUSTOMER_CANCELLATION_REASON) {
                        publishCustomerCancellationSucceeded(entity, payment, now)
                    }
                    recordStep(claim.orderId, OrderCompensationStepState.SUCCEEDED, null, now)
                }
            }

            is GatewayRefundResult.Failed -> {
                refund.fail(result.code, now)
                entity.apply(refund)
                if (entity.reason in COMPENSATION_REASONS) {
                    recordStep(
                        claim.orderId,
                        OrderCompensationStepState.MANUAL_REVIEW,
                        normalized(result.code),
                        now,
                    )
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON) {
                    publishCustomerCancellationDelayed(entity, payment, now)
                }
            }

            is GatewayRefundResult.RetryableFailed -> {
                refund.recordRetryableRequestFailure(result.code, now)
                entity.apply(refund)
                if (entity.reason in COMPENSATION_REASONS) {
                    recordStep(
                        claim.orderId,
                        if (refund.state == RefundState.MANUAL_REVIEW) {
                            OrderCompensationStepState.MANUAL_REVIEW
                        } else {
                            OrderCompensationStepState.UNKNOWN
                        },
                        normalized(result.code),
                        now,
                    )
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON && refund.state == RefundState.MANUAL_REVIEW) {
                    publishCustomerCancellationDelayed(entity, payment, now)
                }
            }

            is GatewayRefundResult.Unknown -> {
                refund.recordUnknown(result.code, now)
                entity.apply(refund)
                val stepState =
                    if (refund.state == RefundState.MANUAL_REVIEW) {
                        OrderCompensationStepState.MANUAL_REVIEW
                    } else {
                        OrderCompensationStepState.UNKNOWN
                    }
                if (entity.reason in COMPENSATION_REASONS) {
                    recordStep(claim.orderId, stepState, normalized(result.code), now)
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON && refund.state == RefundState.MANUAL_REVIEW) {
                    publishCustomerCancellationDelayed(entity, payment, now)
                }
                meterRegistry.counter("beanflow.payment.refund.unknown.count").increment()
            }
        }
        meterRegistry
            .counter(
                "beanflow.payment.refund.attempts",
                "mode",
                claim.mode.name.lowercase(),
                "reason",
                reasonTag(entity.reason),
                "provider",
                "configured",
                "outcome",
                result.outcomeTag(),
            ).increment()
    }

    private fun publishCustomerCancellationSucceeded(
        refund: RefundEntity,
        payment: PaymentEntity,
        now: Instant,
    ) {
        val snapshot = requireCustomerCancellationSnapshot(refund)
        refundEventProducer.publishCustomerCancellationSucceeded(refund, payment, snapshot, now)
        meterRegistry.counter("beanflow.event.customer_cancellation_refund.count", "event_type", "succeeded").increment()
    }

    private fun publishCustomerCancellationDelayed(
        refund: RefundEntity,
        payment: PaymentEntity,
        now: Instant,
    ) {
        val snapshot = requireCustomerCancellationSnapshot(refund)
        refundEventProducer.publishCustomerCancellationDelayed(refund, payment, snapshot, now)
        meterRegistry.counter("beanflow.event.customer_cancellation_refund.count", "event_type", "delayed").increment()
    }

    private fun requireCustomerCancellationSnapshot(refund: RefundEntity): CustomerCancellationPaymentSnapshotEntity {
        val snapshot =
            cancellationSnapshots.findByCancellationRefundId(refund.id)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer cancellation recovery snapshot is missing")
        if (snapshot.orderId != refund.orderId || snapshot.paymentId != refund.paymentId) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer cancellation recovery snapshot conflicts with Refund")
        }
        return snapshot
    }

    private fun reasonTag(reason: String): String =
        when (reason) {
            REASON -> "store_rejection"
            CUSTOMER_CANCELLATION_REASON -> "customer_cancellation"
            PARTIAL_REFUND -> "partial_refund"
            else -> "other"
        }

    private fun recordStep(
        orderId: UUID,
        state: OrderCompensationStepState,
        errorCode: String?,
        now: Instant,
    ) {
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.PAYMENT,
            state,
            errorCode,
            now,
        )
    }

    private fun RefundEntity.toDomain(): Refund =
        Refund.restore(
            id = id,
            paymentId = paymentId,
            orderId = orderId,
            requestedAmountKrw = requestedAmountKrw,
            reason = reason,
            providerIdempotencyKey = providerIdempotencyKey,
            sourceReference = sourceReference,
            createdAt = createdAt,
            state = state,
            succeededAmountKrw = succeededAmountKrw,
            providerRefundReference = providerRefundReference,
            requestAttemptCount = requestAttemptCount,
            lookupAttemptCount = lookupAttemptCount,
            nextAction = nextAction,
            nextAttemptAt = nextAttemptAt,
            providerRequestStartedAt = providerRequestStartedAt,
            claimToken = claimToken,
            claimUntil = claimUntil,
            lastFailureCode = lastFailureCode,
            updatedAt = updatedAt,
        )

    private fun Refund.toEntity(): RefundEntity =
        RefundEntity(
            id = id,
            paymentId = paymentId,
            orderId = orderId,
            requestedAmountKrw = requestedAmountKrw,
            succeededAmountKrw = succeededAmountKrw,
            reason = reason,
            state = state,
            providerRefundReference = providerRefundReference,
            providerIdempotencyKey = providerIdempotencyKey,
            sourceReference = sourceReference,
            attemptCount = attemptCount,
            requestAttemptCount = requestAttemptCount,
            lookupAttemptCount = lookupAttemptCount,
            nextAction = nextAction,
            nextAttemptAt = nextAttemptAt,
            providerRequestStartedAt = providerRequestStartedAt,
            claimToken = claimToken,
            claimUntil = claimUntil,
            lastFailureCode = lastFailureCode,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun RefundEntity.apply(refund: Refund) {
        state = refund.state
        succeededAmountKrw = refund.succeededAmountKrw
        providerRefundReference = refund.providerRefundReference
        attemptCount = refund.attemptCount
        requestAttemptCount = refund.requestAttemptCount
        lookupAttemptCount = refund.lookupAttemptCount
        nextAction = refund.nextAction
        nextAttemptAt = refund.nextAttemptAt
        providerRequestStartedAt = refund.providerRequestStartedAt
        claimToken = refund.claimToken
        claimUntil = refund.claimUntil
        lastFailureCode = refund.lastFailureCode
        updatedAt = refund.updatedAt
    }

    private fun GatewayRefundResult.outcomeTag(): String =
        when (this) {
            is GatewayRefundResult.Succeeded -> "succeeded"
            is GatewayRefundResult.Failed -> "failed"
            is GatewayRefundResult.RetryableFailed -> "retryable_failed"
            is GatewayRefundResult.Unknown -> "unknown"
        }

    private fun sourceReference(event: OrderRejectedV1): String = "event:${event.envelope.eventId}:payment-refund"

    private fun normalized(code: String): String =
        code
            .trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val REASON = "STORE_ORDER_REJECTED"
        const val CUSTOMER_CANCELLATION_REASON = "CUSTOMER_ORDER_CANCELLED"
        const val PARTIAL_REFUND = "PARTIAL_REFUND"
        val COMPENSATION_REASONS = setOf(REASON, CUSTOMER_CANCELLATION_REASON)
    }
}
