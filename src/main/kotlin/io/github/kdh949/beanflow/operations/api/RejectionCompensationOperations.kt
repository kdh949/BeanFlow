package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class RejectionCompensationState {
    PROCESSING,
    RETRY_SCHEDULED,
    UNKNOWN,
    SUCCEEDED,
    MANUAL_REVIEW,
}

enum class RejectionCompensationStepType {
    PAYMENT,
    PICKUP,
    STOCK,
    COUPON,
    POINTS,
    CUSTOMER_NOTIFICATION,
}

enum class RejectionCompensationStepState {
    PROCESSING,
    RETRY_SCHEDULED,
    UNKNOWN,
    SUCCEEDED,
    NOT_REQUIRED,
    MANUAL_REVIEW,
}

data class RejectionCompensationStepView(
    val type: RejectionCompensationStepType,
    val state: RejectionCompensationStepState,
    val attemptCount: Int,
    val lastErrorCode: String?,
)

data class RejectionCompensationCaseView(
    val caseId: UUID,
    val orderId: UUID,
    val policyVersion: Long,
    val state: RejectionCompensationState,
    val steps: List<RejectionCompensationStepView>,
    val updatedAt: Instant,
)

data class OpenRejectionCompensationCaseCommand(
    val caseId: UUID,
    val eventId: UUID,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val sourceReference: String,
    val policy: ExpiredBenefitRestorationPolicySnapshot,
    val paymentRequired: Boolean,
    val couponRequired: Boolean,
    val pointsRequired: Boolean,
    val correlationId: String,
    val now: Instant,
)

interface RejectionCompensationOperations {
    fun open(command: OpenRejectionCompensationCaseCommand): RejectionCompensationCaseView

    fun findByOrderId(orderId: UUID): RejectionCompensationCaseView?

    fun markPublicationManualReview(
        orderId: UUID,
        errorCode: String,
        now: Instant,
    ): RejectionCompensationCaseView
}
