package io.github.kdh949.beanflow.support.internal.domain

import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class SupportCompensationBenefitType {
    POINT,
    COUPON,
}

internal enum class SupportCompensationBand {
    LOW,
    MEDIUM,
    HIGH,
    EXCEPTIONAL,
}

internal enum class SupportCompensationDecision {
    ALLOWED,
    APPROVAL_REQUIRED,
    INVESTIGATION_REQUIRED,
    DENIED,
}

internal enum class SupportCompensationResponsibility {
    PLATFORM,
    STORE,
    SHARED,
    UNDETERMINED,
}

internal enum class SupportCompensationEvidenceBasis {
    STORE_CONSENT,
    OPERATIONS_FINDING,
    CONTRACTUAL_RULE,
}

internal enum class SupportCompensationReasonCode {
    RELATED_ORDER_MISSING,
    AMOUNT_ABOVE_LOW_LIMIT,
    AMOUNT_ABOVE_HIGH_LIMIT,
    AMOUNT_ABOVE_SUPPORTED_LIMIT,
    ORDER_RATIO_ABOVE_LOW_LIMIT,
    REPEATED_CUSTOMER_COMPENSATION,
    STORE_COST_RESPONSIBILITY,
    COST_RESPONSIBILITY_UNDETERMINED,
    DUPLICATE_TERMINAL_INCIDENT,
    INSUFFICIENT_VERIFICATION,
}

internal enum class SupportCompensationLimitScope {
    CUSTOMER,
    ORDER,
    INCIDENT,
    ACTOR,
    STORE,
}

internal data class SupportCompensationLimitRule(
    val scope: SupportCompensationLimitScope,
    val window: Duration,
    val maximumKrw: Long,
) {
    init {
        require(!window.isZero && !window.isNegative) { "rolling window must be positive" }
        require(maximumKrw > 0) { "rolling maximum must be positive" }
    }
}

internal data class SupportCompensationPolicyVersion(
    val id: UUID,
    val code: String,
    val effectiveAt: Instant,
    val lowAmountMaximumKrw: Long,
    val highAmountMaximumKrw: Long,
    val supportedAmountMaximumKrw: Long,
    val lowOrderRatioMaximumBps: Int,
    val limits: List<SupportCompensationLimitRule>,
) {
    init {
        require(code.isNotBlank()) { "policy version code is required" }
        require(lowAmountMaximumKrw > 0) { "low maximum must be positive" }
        require(highAmountMaximumKrw > lowAmountMaximumKrw) { "high maximum must exceed low maximum" }
        require(supportedAmountMaximumKrw > highAmountMaximumKrw) { "supported maximum must exceed high maximum" }
        require(lowOrderRatioMaximumBps in 1..10_000) { "order ratio must be one to ten thousand basis points" }
        require(limits.map { it.scope }.distinct().size == limits.size) { "rolling limit scope must be unique" }
    }

    companion object {
        val INITIAL_V1_ID: UUID = UUID.fromString("90000000-0000-0000-0000-000000000001")

        fun initialV1(effectiveAt: Instant): SupportCompensationPolicyVersion =
            SupportCompensationPolicyVersion(
                id = INITIAL_V1_ID,
                code = "GOODWILL_V1",
                effectiveAt = effectiveAt,
                lowAmountMaximumKrw = 3_000,
                highAmountMaximumKrw = 10_000,
                supportedAmountMaximumKrw = 30_000,
                lowOrderRatioMaximumBps = 5_000,
                limits =
                    listOf(
                        SupportCompensationLimitRule(SupportCompensationLimitScope.CUSTOMER, Duration.ofDays(30), 30_000),
                        SupportCompensationLimitRule(SupportCompensationLimitScope.ORDER, Duration.ofDays(30), 30_000),
                        SupportCompensationLimitRule(SupportCompensationLimitScope.INCIDENT, Duration.ofDays(30), 30_000),
                        SupportCompensationLimitRule(SupportCompensationLimitScope.ACTOR, Duration.ofDays(1), 100_000),
                        SupportCompensationLimitRule(SupportCompensationLimitScope.STORE, Duration.ofDays(1), 300_000),
                    ),
            )
    }
}

internal data class SupportCompensationPolicyInput(
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val orderId: UUID?,
    val orderPaidKrw: Long?,
    val storeId: UUID?,
    val priorCustomerAmountKrw: Long,
    val responsibility: SupportCompensationResponsibility,
    val verificationLevel: VerificationLevel,
    val hasTerminalIncidentBenefit: Boolean,
) {
    init {
        require(amountKrw > 0) { "compensation amount must be positive" }
        require(orderPaidKrw == null || orderPaidKrw > 0) { "order paid amount must be positive" }
        require(priorCustomerAmountKrw >= 0) { "prior customer amount cannot be negative" }
    }
}

internal data class SupportCompensationPolicyResult(
    val policyVersionId: UUID,
    val band: SupportCompensationBand,
    val decision: SupportCompensationDecision,
    val approvalRoute: SupportActionApprovalRoute,
    val requiredVerificationLevel: VerificationLevel,
    val executable: Boolean,
    val reasonCodes: Set<SupportCompensationReasonCode>,
    val evaluatedAt: Instant,
)

internal class SupportCompensationPolicy {
    fun evaluate(
        input: SupportCompensationPolicyInput,
        version: SupportCompensationPolicyVersion,
        evaluatedAt: Instant,
    ): SupportCompensationPolicyResult {
        require(!evaluatedAt.isBefore(version.effectiveAt)) { "policy version is not effective" }

        val reasons = linkedSetOf<SupportCompensationReasonCode>()
        val band = selectBand(input, version, reasons)
        val route = approvalRoute(band)
        val requiredVerification =
            if (band == SupportCompensationBand.HIGH || band == SupportCompensationBand.EXCEPTIONAL) {
                VerificationLevel.ENHANCED
            } else {
                VerificationLevel.BASIC
            }
        val executable =
            !input.hasTerminalIncidentBenefit &&
                input.responsibility != SupportCompensationResponsibility.UNDETERMINED
        val baseDecision =
            when {
                input.hasTerminalIncidentBenefit -> SupportCompensationDecision.DENIED
                band == SupportCompensationBand.LOW -> SupportCompensationDecision.ALLOWED
                band == SupportCompensationBand.MEDIUM -> SupportCompensationDecision.APPROVAL_REQUIRED
                else -> SupportCompensationDecision.INVESTIGATION_REQUIRED
            }
        val verificationSufficient = verificationSatisfies(input.verificationLevel, requiredVerification)
        if (!verificationSufficient) {
            reasons += SupportCompensationReasonCode.INSUFFICIENT_VERIFICATION
        }

        return SupportCompensationPolicyResult(
            policyVersionId = version.id,
            band = band,
            decision = if (verificationSufficient) baseDecision else SupportCompensationDecision.DENIED,
            approvalRoute = route,
            requiredVerificationLevel = requiredVerification,
            executable = executable && verificationSufficient,
            reasonCodes = reasons.toSet(),
            evaluatedAt = evaluatedAt,
        )
    }

    private fun selectBand(
        input: SupportCompensationPolicyInput,
        version: SupportCompensationPolicyVersion,
        reasons: MutableSet<SupportCompensationReasonCode>,
    ): SupportCompensationBand {
        if (input.hasTerminalIncidentBenefit) {
            reasons += SupportCompensationReasonCode.DUPLICATE_TERMINAL_INCIDENT
            return SupportCompensationBand.EXCEPTIONAL
        }
        if (input.orderId == null || input.orderPaidKrw == null || input.storeId == null) {
            reasons += SupportCompensationReasonCode.RELATED_ORDER_MISSING
            return SupportCompensationBand.EXCEPTIONAL
        }
        if (input.responsibility == SupportCompensationResponsibility.UNDETERMINED) {
            reasons += SupportCompensationReasonCode.COST_RESPONSIBILITY_UNDETERMINED
            return SupportCompensationBand.EXCEPTIONAL
        }
        if (input.amountKrw > version.supportedAmountMaximumKrw) {
            reasons += SupportCompensationReasonCode.AMOUNT_ABOVE_SUPPORTED_LIMIT
            return SupportCompensationBand.EXCEPTIONAL
        }
        if (input.responsibility == SupportCompensationResponsibility.STORE ||
            input.responsibility == SupportCompensationResponsibility.SHARED
        ) {
            reasons += SupportCompensationReasonCode.STORE_COST_RESPONSIBILITY
            return SupportCompensationBand.HIGH
        }
        if (input.amountKrw > version.highAmountMaximumKrw) {
            reasons += SupportCompensationReasonCode.AMOUNT_ABOVE_HIGH_LIMIT
            return SupportCompensationBand.HIGH
        }
        if (input.amountKrw > version.lowAmountMaximumKrw) {
            reasons += SupportCompensationReasonCode.AMOUNT_ABOVE_LOW_LIMIT
            return SupportCompensationBand.MEDIUM
        }
        if (exceedsOrderRatio(input.amountKrw, input.orderPaidKrw, version.lowOrderRatioMaximumBps)) {
            reasons += SupportCompensationReasonCode.ORDER_RATIO_ABOVE_LOW_LIMIT
            return SupportCompensationBand.MEDIUM
        }
        if (input.priorCustomerAmountKrw > 0) {
            reasons += SupportCompensationReasonCode.REPEATED_CUSTOMER_COMPENSATION
            return SupportCompensationBand.MEDIUM
        }
        return SupportCompensationBand.LOW
    }

    private fun approvalRoute(band: SupportCompensationBand): SupportActionApprovalRoute =
        when (band) {
            SupportCompensationBand.LOW -> SupportActionApprovalRoute.NONE
            SupportCompensationBand.MEDIUM -> SupportActionApprovalRoute.SUPPORT_MANAGER
            SupportCompensationBand.HIGH,
            SupportCompensationBand.EXCEPTIONAL,
            -> SupportActionApprovalRoute.OPERATIONS
        }

    private fun verificationSatisfies(
        actual: VerificationLevel,
        required: VerificationLevel,
    ): Boolean =
        when (required) {
            VerificationLevel.UNVERIFIED -> true
            VerificationLevel.BASIC -> actual == VerificationLevel.BASIC || actual == VerificationLevel.ENHANCED
            VerificationLevel.ENHANCED -> actual == VerificationLevel.ENHANCED
        }

    private fun exceedsOrderRatio(
        amountKrw: Long,
        orderPaidKrw: Long,
        maximumBps: Int,
    ): Boolean =
        BigInteger.valueOf(amountKrw).multiply(BigInteger.valueOf(10_000)) >
            BigInteger.valueOf(orderPaidKrw).multiply(BigInteger.valueOf(maximumBps.toLong()))
}

internal enum class SupportCompensationFundingIssuer {
    PLATFORM,
    STORE,
}

internal data class SupportCompensationFundingLeg(
    val issuerType: SupportCompensationFundingIssuer,
    val storeId: UUID?,
    val amountKrw: Long,
) {
    init {
        require(amountKrw > 0) { "funding amount must be positive" }
        require((issuerType == SupportCompensationFundingIssuer.STORE) == (storeId != null)) {
            "store funding must identify exactly one store"
        }
    }
}

internal data class SupportCompensationCostSnapshot(
    val responsibility: SupportCompensationResponsibility,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    val evidenceDigest: String?,
    val platformShareBps: Int,
    val storeShareBps: Int,
) {
    init {
        require(platformShareBps in 0..10_000 && storeShareBps in 0..10_000) { "funding shares must be valid basis points" }
        when (responsibility) {
            SupportCompensationResponsibility.PLATFORM -> {
                require(platformShareBps == 10_000 && storeShareBps == 0) { "platform responsibility requires full platform share" }
            }
            SupportCompensationResponsibility.STORE -> {
                require(platformShareBps == 0 && storeShareBps == 10_000) { "store responsibility requires full store share" }
                requireEvidence()
            }
            SupportCompensationResponsibility.SHARED -> {
                require(platformShareBps in 1..9_999 && storeShareBps in 1..9_999) { "shared responsibility requires both shares" }
                require(platformShareBps + storeShareBps == 10_000) { "shared responsibility must total ten thousand basis points" }
                requireEvidence()
            }
            SupportCompensationResponsibility.UNDETERMINED -> {
                require(platformShareBps == 0 && storeShareBps == 0) { "undetermined responsibility cannot allocate cost" }
                require(evidenceBasis == null && evidenceDigest == null) { "undetermined responsibility cannot claim evidence" }
            }
        }
    }

    fun fundingLegs(amountKrw: Long, storeId: UUID?): List<SupportCompensationFundingLeg> {
        require(amountKrw > 0) { "compensation amount must be positive" }
        val platformAmount = allocate(amountKrw, platformShareBps)
        val storeAmount = amountKrw - platformAmount
        return when (responsibility) {
            SupportCompensationResponsibility.PLATFORM ->
                listOf(SupportCompensationFundingLeg(SupportCompensationFundingIssuer.PLATFORM, null, amountKrw))
            SupportCompensationResponsibility.STORE -> {
                requireNotNull(storeId) { "store responsibility requires a store" }
                listOf(SupportCompensationFundingLeg(SupportCompensationFundingIssuer.STORE, storeId, amountKrw))
            }
            SupportCompensationResponsibility.SHARED -> {
                requireNotNull(storeId) { "shared responsibility requires a store" }
                listOf(
                    SupportCompensationFundingLeg(SupportCompensationFundingIssuer.PLATFORM, null, platformAmount),
                    SupportCompensationFundingLeg(SupportCompensationFundingIssuer.STORE, storeId, storeAmount),
                )
            }
            SupportCompensationResponsibility.UNDETERMINED -> emptyList()
        }
    }

    private fun requireEvidence() {
        require(evidenceBasis != null) { "store cost allocation requires an evidence basis" }
        require(evidenceDigest?.matches(HEX_SHA_256) == true) { "store cost allocation requires a SHA-256 evidence digest" }
    }

    private fun allocate(amountKrw: Long, shareBps: Int): Long =
        BigInteger.valueOf(amountKrw)
            .multiply(BigInteger.valueOf(shareBps.toLong()))
            .divide(BigInteger.valueOf(10_000))
            .longValueExact()

    companion object {
        private val HEX_SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}
