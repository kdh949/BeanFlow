package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SupportActionPolicyTest {
    private val evaluatedAt = Instant.parse("2026-08-12T04:00:00Z")
    private val policy = SupportActionPolicy()

    @Test
    fun `pending and paid cancellation or reschedule allow basic action verification`() {
        listOf(SupportActionOrderState.PENDING_PAYMENT, SupportActionOrderState.PAID).forEach { state ->
            listOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE).forEach { action ->
                val result = policy.evaluate(eligibleInput(action = action, state = state), evaluatedAt)

                assertThat(result.decision).isEqualTo(SupportActionDecision.ALLOWED)
                assertThat(result.reasonCodes).containsExactly(SupportActionReasonCode.POLICY_ALLOWED)
                assertThat(result.requiredVerificationLevel).isEqualTo(VerificationLevel.BASIC)
                assertThat(result.expiresAt).isEqualTo(evaluatedAt.plusSeconds(120))
            }
        }
    }

    @Test
    fun `accepted cancellation and reschedule require enhanced verification and approval`() {
        listOf(SupportActionType.ORDER_CANCELLATION, SupportActionType.PICKUP_RESCHEDULE).forEach { action ->
            val insufficient =
                policy.evaluate(
                    eligibleInput(action = action, state = SupportActionOrderState.ACCEPTED)
                        .copy(verificationLevel = VerificationLevel.BASIC),
                    evaluatedAt,
                )
            assertThat(insufficient.decision).isEqualTo(SupportActionDecision.DENIED)
            assertThat(insufficient.reasonCodes).contains(SupportActionReasonCode.INSUFFICIENT_VERIFICATION)

            val result =
                policy.evaluate(
                    eligibleInput(action = action, state = SupportActionOrderState.ACCEPTED)
                        .copy(verificationLevel = VerificationLevel.ENHANCED),
                    evaluatedAt,
                )
            assertThat(result.decision).isEqualTo(SupportActionDecision.APPROVAL_REQUIRED)
            assertThat(result.reasonCodes).containsExactly(SupportActionReasonCode.POLICY_APPROVAL_REQUIRED)
            assertThat(result.approvalRequirements).containsExactly(SupportActionApprovalRequirement.SUPPORT_MANAGER)
        }
    }

    @Test
    fun `post acceptance resolution is limited to preparing ready and completed`() {
        listOf(
            SupportActionOrderState.PREPARING,
            SupportActionOrderState.READY,
            SupportActionOrderState.COMPLETED,
        ).forEach { state ->
            val result =
                policy.evaluate(
                    eligibleInput(SupportActionType.POST_ACCEPTANCE_RESOLUTION, state)
                        .copy(verificationLevel = VerificationLevel.ENHANCED),
                    evaluatedAt,
                )
            assertThat(result.decision).isEqualTo(SupportActionDecision.APPROVAL_REQUIRED)
        }

        val denied =
            policy.evaluate(
                eligibleInput(SupportActionType.POST_ACCEPTANCE_RESOLUTION, SupportActionOrderState.PAID)
                    .copy(verificationLevel = VerificationLevel.ENHANCED),
                evaluatedAt,
            )
        assertThat(denied.decision).isEqualTo(SupportActionDecision.DENIED)
        assertThat(denied.reasonCodes).containsExactly(SupportActionReasonCode.UNSUPPORTED_TARGET_STATE)
    }

    @Test
    fun `unknown security combinations default deny with closed reasons`() {
        val base = eligibleInput()
        val deniedInputs =
            listOf(
                base.copy(caseEligible = false) to SupportActionReasonCode.CASE_NOT_ELIGIBLE,
                base.copy(relationshipMatches = false) to SupportActionReasonCode.TARGET_RELATIONSHIP_MISMATCH,
                base.copy(hasGenericPermission = false) to SupportActionReasonCode.MISSING_PERMISSION,
                base.copy(hasCapabilityPermission = false) to SupportActionReasonCode.MISSING_PERMISSION,
                base.copy(verificationScope = VerificationActionScope.PERSONAL_DATA_REVEAL) to
                    SupportActionReasonCode.VERIFICATION_SCOPE_MISMATCH,
                base.copy(verificationPurpose = VerificationPurpose.CONTACT_CONFIRMATION) to
                    SupportActionReasonCode.VERIFICATION_PURPOSE_MISMATCH,
            )

        deniedInputs.forEach { (input, reason) ->
            val result = policy.evaluate(input, evaluatedAt)
            assertThat(result.decision).isEqualTo(SupportActionDecision.DENIED)
            assertThat(result.reasonCodes).contains(reason)
        }
    }

    @Test
    fun `target version mismatch denies and evaluation freshness is exact`() {
        val result = policy.evaluate(eligibleInput().copy(expectedTargetVersion = 6), evaluatedAt)

        assertThat(result.decision).isEqualTo(SupportActionDecision.DENIED)
        assertThat(result.reasonCodes).containsExactly(SupportActionReasonCode.STALE_TARGET_VERSION)

        val allowed = policy.evaluate(eligibleInput(), evaluatedAt)
        assertThat(allowed.isFresh(evaluatedAt.plusSeconds(119), allowed.targetVersion, allowed.policyVersion)).isTrue()
        assertThat(allowed.isFresh(evaluatedAt.plusSeconds(120), allowed.targetVersion, allowed.policyVersion)).isFalse()
        assertThat(allowed.isFresh(evaluatedAt.plusSeconds(1), allowed.targetVersion + 1, allowed.policyVersion)).isFalse()
        assertThat(allowed.isFresh(evaluatedAt.plusSeconds(1), allowed.targetVersion, "changed-policy")).isFalse()
    }

    private fun eligibleInput(
        action: SupportActionType = SupportActionType.ORDER_CANCELLATION,
        state: SupportActionOrderState = SupportActionOrderState.PAID,
    ) = SupportActionPolicyInput(
        action = action,
        targetState = state,
        expectedTargetVersion = 5,
        currentTargetVersion = 5,
        caseEligible = true,
        relationshipMatches = true,
        hasGenericPermission = true,
        hasCapabilityPermission = true,
        verificationScope = VerificationActionScope.SUPPORT_ACTION,
        verificationPurpose = VerificationPurpose.CASE_RESOLUTION,
        verificationLevel = VerificationLevel.BASIC,
    )
}
