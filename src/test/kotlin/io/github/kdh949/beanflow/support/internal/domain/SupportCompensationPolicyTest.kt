package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SupportCompensationPolicyTest {
    private val evaluatedAt = Instant.parse("2026-08-12T12:00:00Z")
    private val policy = SupportCompensationPolicy()
    private val version = SupportCompensationPolicyVersion.initialV1(effectiveAt = evaluatedAt.minusSeconds(1))

    @Test
    fun `amount boundaries select immutable low medium high and exceptional routes`() {
        val cases =
            listOf(
                3_000L to Triple(SupportCompensationBand.LOW, SupportCompensationDecision.ALLOWED, SupportActionApprovalRoute.NONE),
                3_001L to
                    Triple(
                        SupportCompensationBand.MEDIUM,
                        SupportCompensationDecision.APPROVAL_REQUIRED,
                        SupportActionApprovalRoute.SUPPORT_MANAGER,
                    ),
                10_000L to
                    Triple(
                        SupportCompensationBand.MEDIUM,
                        SupportCompensationDecision.APPROVAL_REQUIRED,
                        SupportActionApprovalRoute.SUPPORT_MANAGER,
                    ),
                10_001L to
                    Triple(
                        SupportCompensationBand.HIGH,
                        SupportCompensationDecision.INVESTIGATION_REQUIRED,
                        SupportActionApprovalRoute.OPERATIONS,
                    ),
                30_000L to
                    Triple(
                        SupportCompensationBand.HIGH,
                        SupportCompensationDecision.INVESTIGATION_REQUIRED,
                        SupportActionApprovalRoute.OPERATIONS,
                    ),
                30_001L to
                    Triple(
                        SupportCompensationBand.EXCEPTIONAL,
                        SupportCompensationDecision.INVESTIGATION_REQUIRED,
                        SupportActionApprovalRoute.OPERATIONS,
                    ),
            )

        cases.forEach { (amount, expected) ->
            val result = policy.evaluate(eligibleInput(amountKrw = amount), version, evaluatedAt)

            assertThat(result.band).isEqualTo(expected.first)
            assertThat(result.decision).isEqualTo(expected.second)
            assertThat(result.approvalRoute).isEqualTo(expected.third)
            assertThat(result.policyVersionId).isEqualTo(version.id)
        }
    }

    @Test
    fun `ratio repeat and store responsibility escalate without trusting the amount band alone`() {
        assertThat(
            policy.evaluate(eligibleInput(amountKrw = 3_000, orderPaidKrw = 5_999), version, evaluatedAt).band,
        ).isEqualTo(SupportCompensationBand.MEDIUM)
        assertThat(
            policy.evaluate(eligibleInput(amountKrw = 3_000, priorCustomerAmountKrw = 1), version, evaluatedAt).band,
        ).isEqualTo(SupportCompensationBand.MEDIUM)

        listOf(SupportCompensationResponsibility.STORE, SupportCompensationResponsibility.SHARED).forEach { responsibility ->
            val result =
                policy.evaluate(
                    eligibleInput(amountKrw = 3_000, responsibility = responsibility),
                    version,
                    evaluatedAt,
                )
            assertThat(result.band).isEqualTo(SupportCompensationBand.HIGH)
            assertThat(result.requiredVerificationLevel).isEqualTo(VerificationLevel.ENHANCED)
            assertThat(result.approvalRoute).isEqualTo(SupportActionApprovalRoute.OPERATIONS)
        }
    }

    @Test
    fun `missing order undetermined responsibility and terminal duplicate are explicit exceptional outcomes`() {
        val missingOrder = policy.evaluate(eligibleInput(orderId = null, orderPaidKrw = null, storeId = null), version, evaluatedAt)
        assertThat(missingOrder.band).isEqualTo(SupportCompensationBand.EXCEPTIONAL)
        assertThat(missingOrder.reasonCodes).contains(SupportCompensationReasonCode.RELATED_ORDER_MISSING)

        val undetermined =
            policy.evaluate(
                eligibleInput(responsibility = SupportCompensationResponsibility.UNDETERMINED),
                version,
                evaluatedAt,
            )
        assertThat(undetermined.band).isEqualTo(SupportCompensationBand.EXCEPTIONAL)
        assertThat(undetermined.executable).isFalse()
        assertThat(undetermined.reasonCodes).contains(SupportCompensationReasonCode.COST_RESPONSIBILITY_UNDETERMINED)

        val duplicate = policy.evaluate(eligibleInput(hasTerminalIncidentBenefit = true), version, evaluatedAt)
        assertThat(duplicate.band).isEqualTo(SupportCompensationBand.EXCEPTIONAL)
        assertThat(duplicate.decision).isEqualTo(SupportCompensationDecision.DENIED)
        assertThat(duplicate.reasonCodes).contains(SupportCompensationReasonCode.DUPLICATE_TERMINAL_INCIDENT)
    }

    @Test
    fun `verification is default deny at the selected route`() {
        val low = policy.evaluate(eligibleInput(verificationLevel = VerificationLevel.UNVERIFIED), version, evaluatedAt)
        assertThat(low.decision).isEqualTo(SupportCompensationDecision.DENIED)
        assertThat(low.reasonCodes).contains(SupportCompensationReasonCode.INSUFFICIENT_VERIFICATION)

        val high =
            policy.evaluate(
                eligibleInput(amountKrw = 10_001, verificationLevel = VerificationLevel.BASIC),
                version,
                evaluatedAt,
            )
        assertThat(high.decision).isEqualTo(SupportCompensationDecision.DENIED)
        assertThat(high.requiredVerificationLevel).isEqualTo(VerificationLevel.ENHANCED)
    }

    @Test
    fun `initial version exposes five immutable rolling rules`() {
        assertThat(version.limits)
            .extracting(SupportCompensationLimitRule::scope, SupportCompensationLimitRule::window, SupportCompensationLimitRule::maximumKrw)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(SupportCompensationLimitScope.CUSTOMER, Duration.ofDays(30), 30_000L),
                org.assertj.core.groups.Tuple.tuple(SupportCompensationLimitScope.ORDER, Duration.ofDays(30), 30_000L),
                org.assertj.core.groups.Tuple.tuple(SupportCompensationLimitScope.INCIDENT, Duration.ofDays(30), 30_000L),
                org.assertj.core.groups.Tuple.tuple(SupportCompensationLimitScope.ACTOR, Duration.ofDays(1), 100_000L),
                org.assertj.core.groups.Tuple.tuple(SupportCompensationLimitScope.STORE, Duration.ofDays(1), 300_000L),
            )
        assertThat(version.id).isEqualTo(SupportCompensationPolicyVersion.INITIAL_V1_ID)
    }

    @Test
    fun `cost snapshot requires evidence and splits shared point funding exactly`() {
        assertThatThrownBy {
            SupportCompensationCostSnapshot(
                SupportCompensationResponsibility.STORE,
                null,
                null,
                0,
                10_000,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        val shared =
            SupportCompensationCostSnapshot(
                SupportCompensationResponsibility.SHARED,
                SupportCompensationEvidenceBasis.STORE_CONSENT,
                "a".repeat(64),
                3_333,
                6_667,
            )
        assertThat(shared.fundingLegs(10_001, UUID.fromString("10000000-0000-0000-0000-000000000001")))
            .extracting(SupportCompensationFundingLeg::issuerType, SupportCompensationFundingLeg::amountKrw)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(SupportCompensationFundingIssuer.PLATFORM, 3_333L),
                org.assertj.core.groups.Tuple.tuple(SupportCompensationFundingIssuer.STORE, 6_668L),
            )
    }

    private fun eligibleInput(
        amountKrw: Long = 3_000,
        orderId: UUID? = UUID.fromString("20000000-0000-0000-0000-000000000001"),
        orderPaidKrw: Long? = 10_000,
        storeId: UUID? = UUID.fromString("30000000-0000-0000-0000-000000000001"),
        priorCustomerAmountKrw: Long = 0,
        responsibility: SupportCompensationResponsibility = SupportCompensationResponsibility.PLATFORM,
        verificationLevel: VerificationLevel = VerificationLevel.ENHANCED,
        hasTerminalIncidentBenefit: Boolean = false,
    ) = SupportCompensationPolicyInput(
        benefitType = SupportCompensationBenefitType.POINT,
        amountKrw = amountKrw,
        orderId = orderId,
        orderPaidKrw = orderPaidKrw,
        storeId = storeId,
        priorCustomerAmountKrw = priorCustomerAmountKrw,
        responsibility = responsibility,
        verificationLevel = verificationLevel,
        hasTerminalIncidentBenefit = hasTerminalIncidentBenefit,
    )
}
