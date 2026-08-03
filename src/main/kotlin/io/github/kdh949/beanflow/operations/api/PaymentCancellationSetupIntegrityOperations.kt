package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class PaymentCancellationSetupMissingArtifact {
    CANCELLATION_REFUND,
    PAYMENT_RECOVERY_SNAPSHOT,
}

enum class PaymentCancellationSetupInvariantViolation {
    SOURCE_MISMATCH,
    AMOUNT_TIE_OUT_MISMATCH,
}

data class DetectPaymentCancellationSetupIssueCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val missingArtifacts: Set<PaymentCancellationSetupMissingArtifact>,
    val invariantViolations: Set<PaymentCancellationSetupInvariantViolation>,
    val errorCode: String,
    val correlationId: String,
    val now: Instant,
)

data class InspectPaymentCancellationSetupCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long? = null,
    val now: Instant,
)

data class DetectedPaymentCancellationSetupIssue(
    val caseId: UUID,
    val detectedAt: Instant,
    val lastErrorCode: String,
)

interface PaymentCancellationSetupIntegrityOperations {
    fun detect(command: DetectPaymentCancellationSetupIssueCommand): DetectedPaymentCancellationSetupIssue

    fun inspect(command: InspectPaymentCancellationSetupCommand): DetectedPaymentCancellationSetupIssue?
}
