package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant

internal enum class SupportActionType {
    ORDER_CANCELLATION,
    PICKUP_RESCHEDULE,
    POST_ACCEPTANCE_RESOLUTION,
}

internal enum class SupportActionOrderState {
    PENDING_PAYMENT,
    PAID,
    ACCEPTED,
    PREPARING,
    READY,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
}

internal enum class SupportActionDecision {
    ALLOWED,
    APPROVAL_REQUIRED,
    DENIED,
}

internal enum class SupportActionReasonCode {
    POLICY_ALLOWED,
    POLICY_APPROVAL_REQUIRED,
    UNSUPPORTED_TARGET_STATE,
    CASE_NOT_ELIGIBLE,
    TARGET_RELATIONSHIP_MISMATCH,
    MISSING_PERMISSION,
    VERIFICATION_SCOPE_MISMATCH,
    VERIFICATION_PURPOSE_MISMATCH,
    INSUFFICIENT_VERIFICATION,
    STALE_TARGET_VERSION,
}

internal enum class SupportActionApprovalRequirement {
    SUPPORT_MANAGER,
}

internal data class SupportActionPolicyInput(
    val action: SupportActionType,
    val targetState: SupportActionOrderState,
    val expectedTargetVersion: Long,
    val currentTargetVersion: Long,
    val caseEligible: Boolean,
    val relationshipMatches: Boolean,
    val hasGenericPermission: Boolean,
    val hasCapabilityPermission: Boolean,
    val verificationScope: VerificationActionScope,
    val verificationPurpose: VerificationPurpose,
    val verificationLevel: VerificationLevel,
)

internal data class SupportActionPolicyResult(
    val decision: SupportActionDecision,
    val reasonCodes: List<SupportActionReasonCode>,
    val requiredVerificationLevel: VerificationLevel,
    val approvalRequirements: List<SupportActionApprovalRequirement>,
    val policyVersion: String,
    val targetVersion: Long,
    val evaluatedAt: Instant,
    val expiresAt: Instant,
) {
    fun isFresh(
        now: Instant,
        currentTargetVersion: Long,
        currentPolicyVersion: String,
    ): Boolean =
        now.isBefore(expiresAt) &&
            targetVersion == currentTargetVersion &&
            policyVersion == currentPolicyVersion
}

internal class SupportActionPolicy {
    fun evaluate(
        input: SupportActionPolicyInput,
        evaluatedAt: Instant,
    ): SupportActionPolicyResult {
        val rule = ruleFor(input.action, input.targetState)
        val denialReasons = mutableListOf<SupportActionReasonCode>()
        if (rule == null) denialReasons += SupportActionReasonCode.UNSUPPORTED_TARGET_STATE
        if (input.expectedTargetVersion != input.currentTargetVersion) {
            denialReasons += SupportActionReasonCode.STALE_TARGET_VERSION
        }
        if (!input.caseEligible) denialReasons += SupportActionReasonCode.CASE_NOT_ELIGIBLE
        if (!input.relationshipMatches) denialReasons += SupportActionReasonCode.TARGET_RELATIONSHIP_MISMATCH
        if (!input.hasGenericPermission || !input.hasCapabilityPermission) {
            denialReasons += SupportActionReasonCode.MISSING_PERMISSION
        }
        if (input.verificationScope != VerificationActionScope.SUPPORT_ACTION) {
            denialReasons += SupportActionReasonCode.VERIFICATION_SCOPE_MISMATCH
        }
        if (input.verificationPurpose != VerificationPurpose.CASE_RESOLUTION) {
            denialReasons += SupportActionReasonCode.VERIFICATION_PURPOSE_MISMATCH
        }
        if (rule != null && input.verificationLevel.ordinal < rule.requiredVerificationLevel.ordinal) {
            denialReasons += SupportActionReasonCode.INSUFFICIENT_VERIFICATION
        }

        val decision = if (denialReasons.isEmpty()) requireNotNull(rule).decision else SupportActionDecision.DENIED
        val reasonCodes =
            when (decision) {
                SupportActionDecision.ALLOWED -> listOf(SupportActionReasonCode.POLICY_ALLOWED)
                SupportActionDecision.APPROVAL_REQUIRED -> listOf(SupportActionReasonCode.POLICY_APPROVAL_REQUIRED)
                SupportActionDecision.DENIED -> denialReasons.distinct()
            }
        val requiredLevel = rule?.requiredVerificationLevel ?: VerificationLevel.ENHANCED
        val approvals =
            if (decision == SupportActionDecision.APPROVAL_REQUIRED) {
                listOf(SupportActionApprovalRequirement.SUPPORT_MANAGER)
            } else {
                emptyList()
            }
        return SupportActionPolicyResult(
            decision = decision,
            reasonCodes = reasonCodes,
            requiredVerificationLevel = requiredLevel,
            approvalRequirements = approvals,
            policyVersion = POLICY_VERSION,
            targetVersion = input.currentTargetVersion,
            evaluatedAt = evaluatedAt,
            expiresAt = evaluatedAt.plus(EVALUATION_TTL),
        )
    }

    private fun ruleFor(
        action: SupportActionType,
        state: SupportActionOrderState,
    ): PolicyRule? =
        when (action) {
            SupportActionType.ORDER_CANCELLATION,
            SupportActionType.PICKUP_RESCHEDULE,
            ->
                when (state) {
                    SupportActionOrderState.PENDING_PAYMENT,
                    SupportActionOrderState.PAID,
                    -> PolicyRule(SupportActionDecision.ALLOWED, VerificationLevel.BASIC)
                    SupportActionOrderState.ACCEPTED ->
                        PolicyRule(SupportActionDecision.APPROVAL_REQUIRED, VerificationLevel.ENHANCED)
                    else -> null
                }
            SupportActionType.POST_ACCEPTANCE_RESOLUTION ->
                when (state) {
                    SupportActionOrderState.PREPARING,
                    SupportActionOrderState.READY,
                    SupportActionOrderState.COMPLETED,
                    -> PolicyRule(SupportActionDecision.APPROVAL_REQUIRED, VerificationLevel.ENHANCED)
                    else -> null
                }
        }

    private data class PolicyRule(
        val decision: SupportActionDecision,
        val requiredVerificationLevel: VerificationLevel,
    )

    companion object {
        const val POLICY_VERSION = "support-action-policy/2026-08-12/v1"
        private val EVALUATION_TTL = Duration.ofMinutes(2)
    }
}
