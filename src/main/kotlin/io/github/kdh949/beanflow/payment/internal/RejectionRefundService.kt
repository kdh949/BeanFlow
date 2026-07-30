package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
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
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ClaimedRefund(
    val refundId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
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
    private val requestLoader: PaymentProviderRequestLoader,
    private val paymentGateway: PaymentGateway,
    private val compensationOperations: RejectionCompensationOperations,
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
                recordStep(event.orderId, RejectionCompensationStepState.SUCCEEDED, null, event.rejectedAt)
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
                recordStep(event.orderId, RejectionCompensationStepState.SUCCEEDED, null, event.rejectedAt)
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
        return refundRepository.findDueIds(now, PageRequest.of(0, limit)).mapNotNull { refundId ->
            val entity = refundRepository.findLockedById(refundId) ?: return@mapNotNull null
            val refund = entity.toDomain()
            val token = identifierSource.next()
            val dueAt = entity.nextAttemptAt ?: entity.claimUntil ?: now
            val mode =
                try {
                    refund.claim(token, now, claimLease, MAX_ATTEMPTS)
                } catch (_: IllegalStateException) {
                    return@mapNotNull null
                }
            entity.apply(refund)
            ClaimedRefund(
                refundId = entity.id,
                paymentId = entity.paymentId,
                orderId = entity.orderId,
                amountKrw = entity.requestedAmountKrw,
                providerIdempotencyKey = entity.providerIdempotencyKey,
                mode = mode,
                attemptCount = entity.attemptCount,
                claimToken = token,
                dueAt = dueAt,
            )
        }
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
                val payment =
                    paymentRepository.findLockedById(claim.paymentId)
                        ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Payment for refund is missing")
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
                recordStep(claim.orderId, RejectionCompensationStepState.SUCCEEDED, null, now)
            }

            is GatewayRefundResult.Failed -> {
                refund.fail(result.code, now)
                entity.apply(refund)
                recordStep(
                    claim.orderId,
                    RejectionCompensationStepState.MANUAL_REVIEW,
                    normalized(result.code),
                    now,
                )
            }

            is GatewayRefundResult.Unknown -> {
                refund.recordUnknown(result.code, now, RETRY_DELAYS, MAX_ATTEMPTS)
                entity.apply(refund)
                val stepState =
                    if (refund.state == RefundState.MANUAL_REVIEW) {
                        RejectionCompensationStepState.MANUAL_REVIEW
                    } else {
                        RejectionCompensationStepState.UNKNOWN
                    }
                recordStep(claim.orderId, stepState, normalized(result.code), now)
                meterRegistry.counter("beanflow.payment.refund.unknown.count").increment()
            }
        }
        meterRegistry
            .counter(
                "beanflow.payment.refund.attempts",
                "mode",
                claim.mode.name.lowercase(),
                "outcome",
                result.outcomeTag(),
            ).increment()
    }

    private fun recordStep(
        orderId: UUID,
        state: RejectionCompensationStepState,
        errorCode: String?,
        now: Instant,
    ) {
        compensationOperations.recordStep(
            orderId,
            RejectionCompensationStepType.PAYMENT,
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
            attemptCount = attemptCount,
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
        const val MAX_ATTEMPTS = 5
        val RETRY_DELAYS: List<Duration> =
            listOf(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
            )
    }
}
