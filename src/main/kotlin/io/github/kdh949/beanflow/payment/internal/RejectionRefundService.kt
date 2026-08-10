package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.CustomerCancellationRefundReconciliationOperations
import io.github.kdh949.beanflow.operations.api.InspectPaymentCancellationSetupCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.operations.api.ScheduleCustomerCancellationRefundLookupCommand
import io.github.kdh949.beanflow.operations.api.ScheduledCustomerCancellationRefundLookup
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
import java.time.temporal.ChronoUnit
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
    val operatorAuthorized: Boolean,
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
    private val setupIntegrity: PaymentCancellationSetupIntegrityOperations,
    private val partialRefundSuccessLedger: PartialRefundSuccessLedger,
    private val refundEventProducer: PaymentRefundEventProducer,
    private val identifierSource: IdentifierSource,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.refund.claim-lease:PT1M}")
    private val claimLease: Duration,
) : CustomerCancellationRefundReconciliationOperations {
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

    @Transactional(propagation = Propagation.MANDATORY)
    override fun scheduleLookup(command: ScheduleCustomerCancellationRefundLookupCommand): ScheduledCustomerCancellationRefundLookup {
        val initialSnapshot =
            cancellationSnapshots.findByOrderId(command.orderId)
                ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Payment recovery snapshot is missing")
        val payment =
            paymentRepository.findLockedById(initialSnapshot.paymentId)
                ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Cancellation Payment is missing")
        val snapshot =
            cancellationSnapshots.findLockedById(initialSnapshot.id)
                ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Payment recovery snapshot is missing")
        val refundId =
            snapshot.cancellationRefundId
                ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Cancellation Refund source is missing")
        val entity =
            refundRepository.findLockedById(refundId)
                ?: conflict(FailureCode.REPROCESSING_NOT_SAFE, "Cancellation Refund is missing")
        requireOperatorReconciliationSource(command, payment, snapshot, entity)
        val previousState = entity.state
        val refund = entity.toDomain()
        try {
            refund.scheduleOperatorReconciliation(command.now)
        } catch (failure: IllegalStateException) {
            conflict(FailureCode.ORDER_STATE_CONFLICT, failure.message ?: "Refund cannot be reconciled")
        }
        entity.apply(refund)
        compensationOperations.reopenPaymentForRefundReconciliation(
            command.orderId,
            command.cancellationOrderVersion,
            "OPERATOR_RECONCILIATION_SCHEDULED",
            command.now,
        )
        return ScheduledCustomerCancellationRefundLookup(previousRefundState = previousState.name)
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
        val operatorAuthorized = refund.operatorReconciliationPending
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
                    if (entity.reason == CUSTOMER_CANCELLATION_REASON &&
                        refund.state == RefundState.MANUAL_REVIEW &&
                        !operatorAuthorized
                    ) {
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
            operatorAuthorized = operatorAuthorized,
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
            operatorAuthorized = false,
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
                    claim.amountKrw,
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
        val recordedAt = now.truncatedTo(ChronoUnit.MICROS)
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
            check(refund.operatorReconciliationPending == claim.operatorAuthorized) {
                "Refund operator reconciliation claim marker does not match"
            }
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
                refund.succeed(result.providerRefundReference, recordedAt)
                payment.succeededRefundAmountKrw = nextRefundedAmount
                payment.updatedAt = recordedAt
                entity.apply(refund)
                if (entity.reason == PARTIAL_REFUND) {
                    val context =
                        settlementContext
                            ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Partial Refund settlement context is missing")
                    partialRefundSuccessLedger.record(entity, payment, recordedAt)
                    refundEventProducer.publishPartial(entity, payment, context, recordedAt)
                } else {
                    refundEventProducer.publishPreAcceptance(entity, payment, recordedAt)
                    if (entity.reason == CUSTOMER_CANCELLATION_REASON) {
                        publishCustomerCancellationSucceeded(entity, payment, recordedAt)
                    }
                    recordStep(claim.orderId, OrderCompensationStepState.SUCCEEDED, null, recordedAt)
                }
            }

            is GatewayRefundResult.Failed -> {
                refund.fail(result.code, recordedAt)
                entity.apply(refund)
                if (entity.reason in COMPENSATION_REASONS) {
                    recordStep(
                        claim.orderId,
                        OrderCompensationStepState.MANUAL_REVIEW,
                        normalized(result.code),
                        recordedAt,
                    )
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON && !claim.operatorAuthorized) {
                    publishCustomerCancellationDelayed(entity, payment, recordedAt)
                }
            }

            is GatewayRefundResult.RetryableFailed -> {
                when {
                    claim.operatorAuthorized -> refund.recordOperatorReconciliationUnknown(result.code, recordedAt)
                    claim.mode == RefundClaimMode.LOOKUP -> refund.recordUnknown(result.code, recordedAt)
                    else -> refund.recordRetryableRequestFailure(result.code, recordedAt)
                }
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
                        recordedAt,
                    )
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON &&
                    refund.state == RefundState.MANUAL_REVIEW &&
                    !claim.operatorAuthorized
                ) {
                    publishCustomerCancellationDelayed(entity, payment, recordedAt)
                }
            }

            is GatewayRefundResult.Unknown -> {
                if (claim.operatorAuthorized) {
                    refund.recordOperatorReconciliationUnknown(result.code, recordedAt)
                } else {
                    refund.recordUnknown(result.code, recordedAt)
                }
                entity.apply(refund)
                val stepState =
                    if (refund.state == RefundState.MANUAL_REVIEW) {
                        OrderCompensationStepState.MANUAL_REVIEW
                    } else {
                        OrderCompensationStepState.UNKNOWN
                    }
                if (entity.reason in COMPENSATION_REASONS) {
                    recordStep(claim.orderId, stepState, normalized(result.code), recordedAt)
                }
                if (entity.reason == CUSTOMER_CANCELLATION_REASON &&
                    refund.state == RefundState.MANUAL_REVIEW &&
                    !claim.operatorAuthorized
                ) {
                    publishCustomerCancellationDelayed(entity, payment, recordedAt)
                }
                meterRegistry.counter("beanflow.payment.refund.unknown.count").increment()
            }
        }
        meterRegistry
            .counter(
                "beanflow.payment.refund.attempts",
                "mode",
                if (claim.operatorAuthorized) "operator_lookup" else claim.mode.name.lowercase(),
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
        requireCompleteCustomerCancellationSetup(refund.orderId, now)
        val snapshot = requireCustomerCancellationSnapshot(refund)
        refundEventProducer.publishCustomerCancellationSucceeded(refund, payment, snapshot, now)
        meterRegistry.counter("beanflow.event.customer_cancellation_refund.count", "event_type", "succeeded").increment()
    }

    private fun publishCustomerCancellationDelayed(
        refund: RefundEntity,
        payment: PaymentEntity,
        now: Instant,
    ) {
        requireCompleteCustomerCancellationSetup(refund.orderId, now)
        val snapshot = requireCustomerCancellationSnapshot(refund)
        refundEventProducer.publishCustomerCancellationDelayed(refund, payment, snapshot, now)
        meterRegistry.counter("beanflow.event.customer_cancellation_refund.count", "event_type", "delayed").increment()
    }

    private fun requireCompleteCustomerCancellationSetup(
        orderId: UUID,
        now: Instant,
    ) {
        val issue =
            setupIntegrity.inspect(
                InspectPaymentCancellationSetupCommand(
                    orderId = orderId,
                    now = now,
                ),
            )
        if (issue != null) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer cancellation payment setup is incomplete")
        }
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
            operatorReconciliationPending = operatorReconciliationPending,
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
            operatorReconciliationPending = operatorReconciliationPending,
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
        operatorReconciliationPending = refund.operatorReconciliationPending
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

    private fun requireOperatorReconciliationSource(
        command: ScheduleCustomerCancellationRefundLookupCommand,
        payment: PaymentEntity,
        snapshot: CustomerCancellationPaymentSnapshotEntity,
        refund: RefundEntity,
    ) {
        val expectedSource =
            "order:${command.orderId}:customer-cancellation:${command.cancellationOrderVersion}:payment"
        val expectedProviderKey =
            "refund:customer-cancellation:${command.orderId}:${command.cancellationOrderVersion}"
        val valid =
            snapshot.orderId == command.orderId &&
                snapshot.cancellationOrderVersion == command.cancellationOrderVersion &&
                snapshot.paymentId == payment.id &&
                payment.orderId == command.orderId &&
                payment.type == PaymentType.EXTERNAL &&
                payment.approvalState == PaymentApprovalState.APPROVED &&
                payment.approvedAmountKrw == snapshot.approvedAmountKrw &&
                payment.succeededRefundAmountKrw == snapshot.succeededRefundAmountBeforeCancellationKrw &&
                snapshot.cancellationRequestedRefundAmountKrw > 0 &&
                snapshot.refundSourceReference == expectedSource &&
                snapshot.providerIdempotencyKey == expectedProviderKey &&
                refund.id == snapshot.cancellationRefundId &&
                refund.orderId == command.orderId &&
                refund.paymentId == payment.id &&
                refund.reason == CUSTOMER_CANCELLATION_REASON &&
                refund.requestedAmountKrw == snapshot.cancellationRequestedRefundAmountKrw &&
                refund.sourceReference == expectedSource &&
                refund.providerIdempotencyKey == expectedProviderKey &&
                refund.succeededAmountKrw == null
        val tieOut =
            try {
                snapshot.approvedAmountKrw ==
                    Math.addExact(
                        snapshot.succeededRefundAmountBeforeCancellationKrw,
                        snapshot.cancellationRequestedRefundAmountKrw,
                    )
            } catch (_: ArithmeticException) {
                false
            }
        if (!valid || !tieOut) {
            conflict(FailureCode.REPROCESSING_NOT_SAFE, "Customer cancellation Refund source is inconsistent")
        }
    }

    private fun sourceReference(event: OrderRejectedV1): String = "event:${event.envelope.eventId}:payment-refund"

    private fun conflict(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

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
