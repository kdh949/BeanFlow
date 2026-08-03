package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class OrderCompensationTrigger {
    STORE_REJECTION,
    CUSTOMER_CANCELLATION,
}

enum class OrderCompensationState {
    PROCESSING,
    RETRY_SCHEDULED,
    UNKNOWN,
    SUCCEEDED,
    MANUAL_REVIEW,
}

enum class OrderCompensationStepType {
    PAYMENT,
    PICKUP,
    STOCK,
    COUPON,
    POINTS,
    CUSTOMER_NOTIFICATION,
}

enum class OrderCompensationStepState {
    PROCESSING,
    RETRY_SCHEDULED,
    UNKNOWN,
    SUCCEEDED,
    NOT_REQUIRED,
    MANUAL_REVIEW,
}

data class OrderCompensationStepView(
    val type: OrderCompensationStepType,
    val state: OrderCompensationStepState,
    val attemptCount: Int,
    val lastErrorCode: String?,
)

data class OrderCompensationBenefitPolicySnapshotView(
    val benefitType: ExpiredBenefitType,
    val policyVersionId: Long,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
)

data class OrderCompensationCaseView(
    val caseId: UUID,
    val orderId: UUID,
    val trigger: OrderCompensationTrigger,
    val terminalOrderVersion: Long,
    val benefitPolicies: List<OrderCompensationBenefitPolicySnapshotView>,
    val state: OrderCompensationState,
    val steps: List<OrderCompensationStepView>,
    val updatedAt: Instant,
)

data class CompensationBenefitPolicyReference(
    val benefitType: ExpiredBenefitType,
    val policyVersionId: Long,
)

data class CompensationStep(
    val type: OrderCompensationStepType,
    val state: OrderCompensationStepState,
    val attemptCount: Int,
    val lastErrorCode: String?,
)

data class CompensationSummary(
    val caseId: UUID,
    val trigger: OrderCompensationTrigger,
    val benefitPolicies: List<CompensationBenefitPolicyReference>,
    val state: OrderCompensationState,
    val steps: List<CompensationStep>,
    val updatedAt: Instant,
)

data class OperatorCompensationView(
    val compensation: CompensationSummary,
    val paymentSetupIssue: PaymentSetupIssue? = null,
    val setupReprocessingCaseId: UUID? = null,
)

data class PaymentSetupIssue(
    val state: String = "SETUP_INCOMPLETE",
    val missingArtifacts: Set<PaymentCancellationSetupMissingArtifact> = emptySet(),
    val invariantViolations: Set<PaymentCancellationSetupInvariantViolation> = emptySet(),
    val detectedAt: Instant,
    val lastErrorCode: String,
)

data class ReadOperatorCompensationCommand(
    val actorId: UUID,
    val orderId: UUID,
    val accessReason: String,
    val now: Instant,
)

interface OperatorCompensationQueryOperations {
    fun read(command: ReadOperatorCompensationCommand): OperatorCompensationView
}

data class OpenOrderCompensationCaseCommand(
    val caseId: UUID,
    val eventId: UUID,
    val orderId: UUID,
    val terminalOrderVersion: Long,
    val customerId: UUID,
    val storeId: UUID,
    val trigger: OrderCompensationTrigger,
    val sourceReference: String,
    val couponPolicy: ExpiredBenefitRestorationPolicySnapshot,
    val pointsPolicy: ExpiredBenefitRestorationPolicySnapshot,
    val paymentRequired: Boolean,
    val couponRequired: Boolean,
    val pointsRequired: Boolean,
    val correlationId: String,
    val now: Instant,
)

interface OrderCompensationOperations {
    fun open(command: OpenOrderCompensationCaseCommand): OrderCompensationCaseView

    fun findByOrderId(orderId: UUID): OrderCompensationCaseView?

    fun markPublicationManualReview(
        orderId: UUID,
        stepType: OrderCompensationStepType,
        errorCode: String,
        now: Instant,
    ): OrderCompensationCaseView

    fun recordStep(
        orderId: UUID,
        stepType: OrderCompensationStepType,
        stepState: OrderCompensationStepState,
        errorCode: String?,
        now: Instant,
    ): OrderCompensationCaseView

    fun reopenPaymentForSetupRepair(
        orderId: UUID,
        cancellationOrderVersion: Long,
        errorCode: String,
        now: Instant,
    ): OrderCompensationCaseView

    fun reopenPaymentForRefundReconciliation(
        orderId: UUID,
        cancellationOrderVersion: Long,
        errorCode: String,
        now: Instant,
    ): OrderCompensationCaseView
}
