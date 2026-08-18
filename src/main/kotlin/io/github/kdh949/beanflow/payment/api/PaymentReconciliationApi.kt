package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

enum class PaymentReconciliationWorkKind {
    APPROVAL_LOOKUP,
    LATE_VOID,
    LATE_REFUND,
}

data class ClaimedPaymentReconciliation(
    val workId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val kind: PaymentReconciliationWorkKind,
    val attemptCount: Int,
    val claimToken: UUID,
    val dueAt: Instant,
    val requestedAmountKrw: Long,
    val currency: String,
    val correlationId: String,
)

enum class ProviderRecoveryOutcome {
    SUCCEEDED,
    UNAVAILABLE,
    UNKNOWN,
}

data class ProviderRecoveryResult(
    val outcome: ProviderRecoveryOutcome,
    val code: String,
)

data class PaymentReconciliationResponseBodies(
    val completedResponseBody: String,
    val manualReviewResponseBody: String,
)

interface PaymentReconciliationOperations {
    fun claimDue(
        now: Instant,
        limit: Int,
    ): List<ClaimedPaymentReconciliation>

    fun requestProviderLookup(paymentId: UUID): ProviderPaymentResult

    fun requestProviderRecovery(work: ClaimedPaymentReconciliation): ProviderRecoveryResult

    fun recordUnknown(
        work: ClaimedPaymentReconciliation,
        responseStatus: Int,
        responseBody: String,
        manualReviewResponseBody: String,
        code: String,
        now: Instant,
    ): ExternalPaymentView

    fun recordRecovery(
        work: ClaimedPaymentReconciliation,
        result: ProviderRecoveryResult,
        responseBodies: PaymentReconciliationResponseBodies,
        now: Instant,
    )
}
