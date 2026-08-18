package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.ReprocessingCaseOperations
import io.github.kdh949.beanflow.payment.api.ClaimedPaymentReconciliation
import io.github.kdh949.beanflow.payment.api.ExternalPaymentView
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationResponseBodies
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationWorkKind
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderRecoveryOutcome
import io.github.kdh949.beanflow.payment.api.ProviderRecoveryResult
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
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

@Service
internal class PaymentReconciliationService(
    private val reconciliationRepository: PaymentReconciliationJpaRepository,
    private val paymentRepository: PaymentJpaRepository,
    private val idempotencyRepository: PaymentIdempotencyJpaRepository,
    private val identifierSource: IdentifierSource,
    private val requestLoader: PaymentProviderRequestLoader,
    private val gateway: PaymentGateway,
    private val reprocessingCaseOperations: ReprocessingCaseOperations,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.reconciliation.claim-lease:PT1M}")
    private val claimLease: Duration,
) : PaymentReconciliationOperations {
    @Transactional
    override fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedPaymentReconciliation> {
        require(limit in 1..100)
        return reconciliationRepository.findDueIds(now, PageRequest.of(0, limit)).mapNotNull { workId ->
            val work = reconciliationRepository.findLockedById(workId) ?: return@mapNotNull null
            if (!work.isClaimable(now)) return@mapNotNull null
            val payment =
                paymentRepository.findById(work.paymentId).orElse(null)
                    ?: dependencyFailure("Payment for reconciliation is missing")
            val customerId =
                payment.customerId
                    ?: dependencyFailure("External payment customer is missing")
            val claimToken = identifierSource.next()
            work.status = ReconciliationStatus.PROCESSING
            work.claimToken = claimToken
            work.claimUntil = now.plus(claimLease)
            work.updatedAt = now
            ClaimedPaymentReconciliation(
                workId = work.id,
                paymentId = payment.id,
                orderId = payment.orderId,
                customerId = customerId,
                kind = work.kind.toApi(),
                attemptCount = work.attemptCount,
                claimToken = claimToken,
                dueAt = work.nextAttemptAt,
                requestedAmountKrw = payment.requestedAmountKrw,
                currency = payment.currency,
                correlationId = payment.correlationId,
            )
        }
    }

    override fun requestProviderLookup(paymentId: UUID): ProviderPaymentResult = gateway.lookup(requestLoader.loadLookup(paymentId))

    override fun requestProviderRecovery(work: ClaimedPaymentReconciliation): ProviderRecoveryResult {
        val request = requestLoader.loadLookup(work.paymentId)
        val result =
            when (work.kind) {
                PaymentReconciliationWorkKind.LATE_VOID -> {
                    gateway.void(request, "payment:${work.paymentId}:late-void")
                }

                PaymentReconciliationWorkKind.LATE_REFUND -> {
                    gateway.refund(
                        request,
                        work.requestedAmountKrw,
                        "payment:${work.paymentId}:late-refund",
                    )
                }

                PaymentReconciliationWorkKind.APPROVAL_LOOKUP -> {
                    throw DomainFailure(FailureCode.INVALID_REQUEST, "Approval lookup is not a recovery operation")
                }
            }
        return when (result) {
            GatewayRecoveryResult.Succeeded -> {
                ProviderRecoveryResult(ProviderRecoveryOutcome.SUCCEEDED, "SUCCEEDED")
            }

            GatewayRecoveryResult.Unavailable -> {
                ProviderRecoveryResult(ProviderRecoveryOutcome.UNAVAILABLE, "UNAVAILABLE")
            }

            is GatewayRecoveryResult.Unknown -> {
                ProviderRecoveryResult(ProviderRecoveryOutcome.UNKNOWN, result.code)
            }
        }
    }

    @Transactional
    override fun recordUnknown(
        work: ClaimedPaymentReconciliation,
        responseStatus: Int,
        responseBody: String,
        manualReviewResponseBody: String,
        code: String,
        now: Instant,
    ): ExternalPaymentView {
        val entity =
            paymentRepository.findLockedById(work.paymentId)
                ?: dependencyFailure("Payment for reconciliation is missing")
        val locked = lockClaim(work)
        require(locked.kind == ReconciliationKind.APPROVAL_LOOKUP)
        val nextAttempt = locked.attemptCount + 1
        locked.attemptCount = nextAttempt
        locked.lastFailureCode = normalized(code)
        val idempotency =
            idempotencyRepository.findByPaymentId(work.paymentId)
                ?: dependencyFailure("Payment idempotency record is missing")
        if (entity.approvalState == PaymentApprovalState.APPROVING) {
            entity.approvalState = PaymentApprovalState.UNKNOWN
        }
        entity.updatedAt = now
        idempotency.responseStatus = responseStatus
        idempotency.responseBody = responseBody
        if (nextAttempt >= MAX_ATTEMPTS) {
            entity.approvalState = PaymentApprovalState.MANUAL_REVIEW
            locked.status = ReconciliationStatus.MANUAL_REVIEW
            idempotency.status = PaymentIdempotencyStatus.MANUAL_REVIEW
            idempotency.responseBody = manualReviewResponseBody
            openManualCase(entity, locked, now)
        } else {
            locked.status = ReconciliationStatus.RETRY_SCHEDULED
            locked.nextAttemptAt = now.plus(ExternalPaymentService.RECONCILIATION_DELAYS[nextAttempt])
            idempotency.status =
                if (entity.approvalState == PaymentApprovalState.RECONCILING) {
                    PaymentIdempotencyStatus.RECONCILING
                } else {
                    PaymentIdempotencyStatus.UNKNOWN
                }
        }
        clearClaim(locked, now)
        meterRegistry
            .counter(
                "beanflow.payment.reconciliation.attempts",
                "outcome",
                if (nextAttempt >= MAX_ATTEMPTS) "manual_review" else "unknown",
            ).increment()
        return entity.toReconciliationView(locked)
    }

    @Transactional
    override fun recordRecovery(
        work: ClaimedPaymentReconciliation,
        result: ProviderRecoveryResult,
        responseBodies: PaymentReconciliationResponseBodies,
        now: Instant,
    ) {
        val entity =
            paymentRepository.findLockedById(work.paymentId)
                ?: dependencyFailure("Payment for recovery is missing")
        val locked = lockClaim(work)
        require(locked.kind != ReconciliationKind.APPROVAL_LOOKUP)
        locked.attemptCount += 1
        locked.lastFailureCode = normalized(result.code)
        when {
            result.outcome == ProviderRecoveryOutcome.SUCCEEDED -> {
                locked.status = ReconciliationStatus.SUCCEEDED
                completeRecoveryIdempotency(entity, locked, responseBodies.completedResponseBody, now)
            }

            locked.kind == ReconciliationKind.LATE_VOID &&
                result.outcome == ProviderRecoveryOutcome.UNAVAILABLE -> {
                locked.status = ReconciliationStatus.SUCCEEDED
                scheduleRefund(entity, now)
            }

            locked.attemptCount >= MAX_ATTEMPTS -> {
                locked.status = ReconciliationStatus.MANUAL_REVIEW
                entity.approvalState = PaymentApprovalState.MANUAL_REVIEW
                entity.updatedAt = now
                val idempotency =
                    idempotencyRepository.findByPaymentId(entity.id)
                        ?: dependencyFailure("Payment idempotency record is missing")
                idempotency.status = PaymentIdempotencyStatus.MANUAL_REVIEW
                idempotency.responseStatus = 202
                idempotency.responseBody = responseBodies.manualReviewResponseBody
                openManualCase(entity, locked, now)
            }

            else -> {
                locked.status = ReconciliationStatus.RETRY_SCHEDULED
                locked.nextAttemptAt =
                    now.plus(
                        ExternalPaymentService.RECONCILIATION_DELAYS[locked.attemptCount],
                    )
            }
        }
        clearClaim(locked, now)
        meterRegistry
            .counter(
                when (locked.kind) {
                    ReconciliationKind.LATE_VOID -> "beanflow.payment.void.attempts"
                    ReconciliationKind.LATE_REFUND -> "beanflow.payment.refund.attempts"
                    else -> error("Unexpected recovery kind")
                },
                "outcome",
                result.outcome.name.lowercase(),
            ).increment()
    }

    private fun completeRecoveryIdempotency(
        payment: PaymentEntity,
        work: PaymentReconciliationEntity,
        responseBody: String,
        now: Instant,
    ) {
        val idempotency =
            idempotencyRepository.findByPaymentId(payment.id)
                ?: dependencyFailure("Payment idempotency record is missing")
        idempotency.status = PaymentIdempotencyStatus.COMPLETED
        idempotency.responseStatus = 200
        idempotency.responseBody = responseBody
        idempotency.terminalAt = now
        payment.updatedAt = now
        work.updatedAt = now
    }

    private fun scheduleRefund(
        payment: PaymentEntity,
        now: Instant,
    ) {
        if (reconciliationRepository.findByPaymentIdAndKind(payment.id, ReconciliationKind.LATE_REFUND) != null) {
            return
        }
        reconciliationRepository.save(
            PaymentReconciliationEntity(
                id = identifierSource.next(),
                paymentId = payment.id,
                kind = ReconciliationKind.LATE_REFUND,
                status = ReconciliationStatus.SCHEDULED,
                attemptCount = 0,
                nextAttemptAt = now,
                sourceReference = "payment:${payment.id}:late-refund",
                lastFailureCode = "VOID_UNAVAILABLE",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun openManualCase(
        payment: PaymentEntity,
        work: PaymentReconciliationEntity,
        now: Instant,
    ) {
        reprocessingCaseOperations.openPaymentCase(
            OpenReprocessingCaseCommand(
                ownerReference = work.sourceReference,
                reason = "PAYMENT_RECONCILIATION_ATTEMPTS_EXHAUSTED",
                correlationId = payment.correlationId,
                now = now,
            ),
        )
    }

    private fun lockClaim(claim: ClaimedPaymentReconciliation): PaymentReconciliationEntity {
        val work =
            reconciliationRepository.findLockedById(claim.workId)
                ?: dependencyFailure("Claimed reconciliation work is missing")
        if (
            work.status != ReconciliationStatus.PROCESSING ||
            work.claimToken != claim.claimToken ||
            work.paymentId != claim.paymentId
        ) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Reconciliation claim is no longer owned")
        }
        return work
    }

    private fun PaymentReconciliationEntity.isClaimable(now: Instant): Boolean =
        (
            (status == ReconciliationStatus.SCHEDULED || status == ReconciliationStatus.RETRY_SCHEDULED) &&
                !now.isBefore(nextAttemptAt)
        ) ||
            (status == ReconciliationStatus.PROCESSING && claimUntil?.let { !now.isBefore(it) } == true)

    private fun clearClaim(
        work: PaymentReconciliationEntity,
        now: Instant,
    ) {
        work.claimToken = null
        work.claimUntil = null
        work.updatedAt = now
    }

    private fun PaymentEntity.toReconciliationView(work: PaymentReconciliationEntity) =
        ExternalPaymentView(
            paymentId = id,
            orderId = orderId,
            type = type.name,
            approvalState = approvalState.name,
            approvedAmountKrw = approvedAmountKrw,
            currency = currency,
            recoveryState =
                when (work.status) {
                    ReconciliationStatus.SCHEDULED -> "REQUESTED"
                    ReconciliationStatus.RETRY_SCHEDULED -> "RECONCILING"
                    else -> work.status.name
                },
            updatedAt = updatedAt,
            correlationId = correlationId,
        )

    private fun ReconciliationKind.toApi(): PaymentReconciliationWorkKind = PaymentReconciliationWorkKind.valueOf(name)

    private fun normalized(code: String): String =
        code
            .trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    private fun dependencyFailure(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
