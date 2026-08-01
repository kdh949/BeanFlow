package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class ExpiredBenefitRestorationTrigger {
    STORE_REJECTION,
    CUSTOMER_CANCELLATION,
    PARTIAL_REFUND,
}

enum class ExpiredBenefitType {
    COUPON,
    POINTS,
}

enum class ExpiredBenefitRestorationMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

/**
 * Internal immutable policy snapshot consumed by existing owner flows.
 *
 * Plan 30 replaces the legacy no-key [ExpiredBenefitRestorationPolicyOperations.current]
 * consumer with two keyed snapshots. Keeping this value free of HTTP-only key fields prevents
 * an accidental change to the pre-release rejection event while Plan 11 builds the shared policy
 * foundation.
 */
data class ExpiredBenefitRestorationPolicySnapshot(
    val policyVersion: Long,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
    val effectiveAt: Instant,
    val updatedBy: UUID,
    val reason: String,
)

data class ExpiredBenefitRestorationPolicyHead(
    val policyVersionId: Long,
    val trigger: ExpiredBenefitRestorationTrigger,
    val benefitType: ExpiredBenefitType,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
    val effectiveAt: Instant,
    val updatedBy: UUID,
    val reason: String,
)

data class ListExpiredBenefitRestorationPoliciesCommand(
    val actorId: UUID,
    val accessReason: String,
    val now: Instant,
)

data class UpdateExpiredBenefitRestorationPolicyCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val trigger: ExpiredBenefitRestorationTrigger,
    val benefitType: ExpiredBenefitType,
    val expectedPolicyVersionId: Long,
    val mode: ExpiredBenefitRestorationMode,
    val compensationValidityDays: Int,
    val reason: String,
    val now: Instant,
)

interface ExpiredBenefitRestorationPolicyOperations {
    /**
     * Legacy store-rejection snapshot used only until Plan 30 connects both benefit heads.
     */
    fun current(): ExpiredBenefitRestorationPolicySnapshot =
        current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.POINTS)

    fun current(
        trigger: ExpiredBenefitRestorationTrigger,
        benefitType: ExpiredBenefitType,
    ): ExpiredBenefitRestorationPolicySnapshot

    fun listCurrent(command: ListExpiredBenefitRestorationPoliciesCommand): List<ExpiredBenefitRestorationPolicyHead>

    fun update(command: UpdateExpiredBenefitRestorationPolicyCommand): ExpiredBenefitRestorationPolicyHead
}
