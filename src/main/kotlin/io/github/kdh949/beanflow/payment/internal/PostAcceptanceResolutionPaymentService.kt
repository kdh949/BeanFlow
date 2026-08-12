package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionOrderState
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionPaymentOperations
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionRefundState
import io.github.kdh949.beanflow.payment.api.PostAcceptanceResolutionRefundView
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.github.kdh949.beanflow.payment.api.RequestPostAcceptanceResolutionRefundCommand
import io.github.kdh949.beanflow.payment.api.SchedulePostAcceptanceResolutionRefundReconciliationCommand
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.payment.internal.domain.Refund
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
internal class PostAcceptanceResolutionPaymentService(
    private val payments: PaymentJpaRepository,
    private val refunds: RefundJpaRepository,
    private val refundService: RejectionRefundService,
    private val identifiers: IdentifierSource,
) : PostAcceptanceResolutionPaymentOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun request(command: RequestPostAcceptanceResolutionRefundCommand): PostAcceptanceResolutionRefundView {
        validate(command)
        val recordedAt = command.now.truncatedTo(ChronoUnit.MICROS)
        val payment =
            payments.findLockedByOrderId(command.orderId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved Payment is missing")
        requireRefundable(payment)
        refunds.findBySourceReference(command.sourceReference)?.let { return it.exactReplay(command) }
        refunds.findUnresolvedByPaymentId(payment.id).takeIf { it.isNotEmpty() }?.let {
            fail(FailureCode.PAYMENT_REFUND_UNRESOLVED, "Another Refund result is unresolved")
        }
        val approved = payment.approvedAmountKrw ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Approved amount is missing")
        if (Math.addExact(payment.succeededRefundAmountKrw, command.amountKrw) > approved) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Cumulative Refund exceeds the approved amount")
        }
        val refund =
            Refund.request(
                id = identifiers.next(),
                paymentId = payment.id,
                orderId = command.orderId,
                requestedAmountKrw = command.amountKrw,
                reason = REASON,
                providerIdempotencyKey = "refund:support-resolution:${command.resolutionId}",
                sourceReference = command.sourceReference,
                now = recordedAt,
            )
        return refunds
            .saveAndFlush(
                RefundEntity(
                    id = refund.id,
                    paymentId = refund.paymentId,
                    orderId = refund.orderId,
                    requestedAmountKrw = refund.requestedAmountKrw,
                    reason = refund.reason,
                    state = refund.state,
                    providerIdempotencyKey = refund.providerIdempotencyKey,
                    sourceReference = refund.sourceReference,
                    actorId = command.actorId,
                    idempotencyKey = "support-resolution:${command.resolutionId}",
                    payloadHash = command.payloadHash,
                    correlationId = command.correlationId,
                    supportResolutionId = command.resolutionId,
                    resolutionOrderState = command.orderState.name,
                    resolutionOrderCompletedAt = command.orderCompletedAt,
                    resolutionOrderVersion = command.orderVersion,
                    attemptCount = 0,
                    requestAttemptCount = 0,
                    lookupAttemptCount = 0,
                    nextAction = refund.nextAction,
                    nextAttemptAt = refund.nextAttemptAt,
                    createdAt = refund.createdAt,
                    updatedAt = refund.updatedAt,
                ),
            ).toView(replayed = false)
    }

    @Transactional(readOnly = true)
    override fun findBySourceReference(sourceReference: String): PostAcceptanceResolutionRefundView? =
        refunds.findBySourceReference(sourceReference)?.takeIf { it.reason == REASON }?.toView(replayed = true)

    override fun execute(
        refundId: UUID,
        now: Instant,
    ): PostAcceptanceResolutionRefundView {
        current(refundId)?.takeIf { it.state !in CLAIMABLE_STATES }?.let { return it }
        val claim = refundService.claimResolutionOne(refundId, now)
        val result =
            try {
                refundService.callProvider(claim)
            } catch (_: ProviderTransportFailure) {
                GatewayRefundResult.Unknown("PROVIDER_CALL_FAILED")
            }
        refundService.recordResolutionResult(claim, result, now)
        return current(refundId) ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Resolution Refund disappeared")
    }

    override fun scheduleReconciliation(
        command: SchedulePostAcceptanceResolutionRefundReconciliationCommand,
    ): PostAcceptanceResolutionRefundView =
        refundService
            .scheduleResolutionLookup(
                command.resolutionId,
                command.refundId,
                command.sourceReference,
                command.now,
            ).toView(replayed = false)

    @Transactional(readOnly = true)
    fun current(refundId: UUID): PostAcceptanceResolutionRefundView? =
        refunds
            .findById(refundId)
            .orElse(null)
            ?.takeIf { it.reason == REASON }
            ?.toView(replayed = false)

    private fun validate(command: RequestPostAcceptanceResolutionRefundCommand) {
        if (command.amountKrw <= 0 || command.orderVersion < 0 ||
            command.sourceReference.isBlank() || command.sourceReference != command.sourceReference.trim() ||
            command.sourceReference.length > 240 || !command.payloadHash.matches(SHA_256) ||
            command.correlationId.isBlank() || command.correlationId != command.correlationId.trim() ||
            command.correlationId.length > 160 ||
            ((command.orderState == PostAcceptanceResolutionOrderState.COMPLETED) != (command.orderCompletedAt != null))
        ) {
            fail(FailureCode.INVALID_REQUEST, "Support Resolution Refund command is invalid")
        }
    }

    private fun requireRefundable(payment: PaymentEntity) {
        if (payment.type != PaymentType.EXTERNAL || payment.approvalState != PaymentApprovalState.APPROVED ||
            payment.currency != "KRW" || payment.approvedAmountKrw == null
        ) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment is not refundable")
        }
    }

    private fun RefundEntity.exactReplay(command: RequestPostAcceptanceResolutionRefundCommand): PostAcceptanceResolutionRefundView {
        if (reason != REASON || orderId != command.orderId || requestedAmountKrw != command.amountKrw ||
            actorId != command.actorId || payloadHash != command.payloadHash || correlationId != command.correlationId ||
            supportResolutionId != command.resolutionId || resolutionOrderState != command.orderState.name ||
            resolutionOrderCompletedAt != command.orderCompletedAt || resolutionOrderVersion != command.orderVersion
        ) {
            fail(FailureCode.IDEMPOTENCY_KEY_REUSED, "Resolution Refund source was reused with another payload")
        }
        return toView(replayed = true)
    }

    private fun RefundEntity.toView(replayed: Boolean): PostAcceptanceResolutionRefundView =
        PostAcceptanceResolutionRefundView(
            refundId = id,
            orderId = orderId,
            amountKrw = requestedAmountKrw,
            state = PostAcceptanceResolutionRefundState.valueOf(state.name),
            sourceReference = sourceReference,
            updatedAt = updatedAt,
            replayed = replayed,
        )

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private companion object {
        const val REASON = "SUPPORT_POST_ACCEPTANCE_RESOLUTION"
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val CLAIMABLE_STATES =
            setOf(
                PostAcceptanceResolutionRefundState.REQUESTED,
                PostAcceptanceResolutionRefundState.RETRY_SCHEDULED,
                PostAcceptanceResolutionRefundState.UNKNOWN,
            )
    }
}
