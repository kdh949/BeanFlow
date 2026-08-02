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
}
