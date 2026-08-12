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
    STALE_TARGET_VERSION,
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
    val targetVersionMatches: Boolean = true,
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
                input.responsibility != SupportCompensationResponsibility.UNDETERMINED &&
                input.targetVersionMatches &&
                (input.responsibility == SupportCompensationResponsibility.PLATFORM || input.storeId != null)
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
        if (!input.targetVersionMatches) {
            reasons += SupportCompensationReasonCode.STALE_TARGET_VERSION
        }

        return SupportCompensationPolicyResult(
            policyVersionId = version.id,
            band = band,
            decision = if (verificationSufficient && input.targetVersionMatches) baseDecision else SupportCompensationDecision.DENIED,
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

    fun fundingLegs(
        amountKrw: Long,
        storeId: UUID?,
    ): List<SupportCompensationFundingLeg> {
        require(amountKrw > 0) { "compensation amount must be positive" }
        val platformAmount = allocate(amountKrw, platformShareBps)
        val storeAmount = amountKrw - platformAmount
        return when (responsibility) {
            SupportCompensationResponsibility.PLATFORM -> {
                listOf(SupportCompensationFundingLeg(SupportCompensationFundingIssuer.PLATFORM, null, amountKrw))
            }

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

            SupportCompensationResponsibility.UNDETERMINED -> {
                emptyList()
            }
        }
    }

    private fun requireEvidence() {
        require(evidenceBasis != null) { "store cost allocation requires an evidence basis" }
        require(evidenceDigest?.matches(HEX_SHA_256) == true) { "store cost allocation requires a SHA-256 evidence digest" }
    }

    private fun allocate(
        amountKrw: Long,
        shareBps: Int,
    ): Long =
        BigInteger
            .valueOf(amountKrw)
            .multiply(BigInteger.valueOf(shareBps.toLong()))
            .divide(BigInteger.valueOf(10_000))
            .longValueExact()

    companion object {
        private val HEX_SHA_256 = Regex("^[0-9a-f]{64}$")

        fun platform(): SupportCompensationCostSnapshot =
            SupportCompensationCostSnapshot(
                responsibility = SupportCompensationResponsibility.PLATFORM,
                evidenceBasis = null,
                evidenceDigest = null,
                platformShareBps = 10_000,
                storeShareBps = 0,
            )
    }
}

internal enum class SupportCompensationRequestState {
    AWAITING_APPROVAL,
    READY_FOR_EXECUTION,
    BENEFIT_ISSUED,
    NOTIFICATION_RETRY,
    NOTIFICATION_ACCEPTED,
    MANUAL_REVIEW,
}

internal data class SupportCompensationBenefitChange(
    val benefitId: UUID,
    val previousState: SupportCompensationRequestState,
    val currentState: SupportCompensationRequestState,
    val requestVersion: Long,
    val occurredAt: Instant,
    val replayed: Boolean,
)

internal class SupportCompensationRequest private constructor(
    val id: UUID,
    val supportCaseId: UUID,
    val customerId: UUID,
    val incidentId: UUID,
    val orderId: UUID?,
    val storeId: UUID?,
    val requesterActorId: UUID,
    var executorActorId: UUID,
    val benefitType: SupportCompensationBenefitType,
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    val policyVersionId: UUID,
    val band: SupportCompensationBand,
    val route: SupportActionApprovalRoute,
    val verificationSessionId: UUID,
    val targetVersion: Long,
    val costSnapshot: SupportCompensationCostSnapshot,
    val payloadDigest: String,
    val evidenceDigest: String,
    val actionRequestId: UUID?,
    var state: SupportCompensationRequestState,
    var terminalBenefitId: UUID?,
    var notificationDeliveryId: UUID?,
    var notificationFailureCode: String?,
    var version: Long,
    private var lastChangedAt: Instant,
) {
    fun markApprovalReady(
        approvalRequestId: UUID,
        expectedVersion: Long,
        occurredAt: Instant,
    ) {
        check(state == SupportCompensationRequestState.AWAITING_APPROVAL) { "Compensation request is not awaiting approval" }
        check(actionRequestId == approvalRequestId) { "Compensation approval request binding is stale" }
        check(version == expectedVersion) { "Compensation request version is stale" }
        requireChronology(occurredAt)
        state = SupportCompensationRequestState.READY_FOR_EXECUTION
        version += 1
        lastChangedAt = occurredAt
    }

    fun completeBenefit(
        benefitId: UUID,
        actorId: UUID,
        exactPayloadDigest: String,
        currentTargetVersion: Long,
        occurredAt: Instant,
    ): SupportCompensationBenefitChange {
        require(actorId == executorActorId) { "Only the assigned actor can execute compensation" }
        check(exactPayloadDigest == payloadDigest) { "Compensation payload binding is stale" }
        check(currentTargetVersion == targetVersion) { "Compensation target version is stale" }
        requireChronology(occurredAt)
        terminalBenefitId?.let { existing ->
            check(existing == benefitId) { "Compensation request already issued another benefit" }
            return SupportCompensationBenefitChange(existing, state, state, version, occurredAt, true)
        }
        check(state == SupportCompensationRequestState.READY_FOR_EXECUTION) { "Compensation request is not ready for execution" }
        val previous = state
        terminalBenefitId = benefitId
        state = SupportCompensationRequestState.BENEFIT_ISSUED
        version += 1
        lastChangedAt = occurredAt
        return SupportCompensationBenefitChange(benefitId, previous, state, version, occurredAt, false)
    }

    fun markNotificationRetry(
        failureCode: String,
        occurredAt: Instant,
    ) {
        check(state == SupportCompensationRequestState.BENEFIT_ISSUED || state == SupportCompensationRequestState.NOTIFICATION_RETRY) {
            "Only issued compensation can schedule notification retry"
        }
        require(failureCode.matches(FAILURE_CODE)) { "Notification failure code is invalid" }
        requireChronology(occurredAt)
        state = SupportCompensationRequestState.NOTIFICATION_RETRY
        notificationFailureCode = failureCode
        version += 1
        lastChangedAt = occurredAt
    }

    fun completeNotification(
        deliveryId: UUID,
        occurredAt: Instant,
    ) {
        if (state == SupportCompensationRequestState.NOTIFICATION_ACCEPTED && notificationDeliveryId == deliveryId) return
        check(state == SupportCompensationRequestState.BENEFIT_ISSUED || state == SupportCompensationRequestState.NOTIFICATION_RETRY) {
            "Compensation notification is not pending"
        }
        requireChronology(occurredAt)
        state = SupportCompensationRequestState.NOTIFICATION_ACCEPTED
        notificationDeliveryId = deliveryId
        notificationFailureCode = null
        version += 1
        lastChangedAt = occurredAt
    }

    fun markManualReview(
        failureCode: String,
        occurredAt: Instant,
    ) {
        check(terminalBenefitId != null) { "Only terminal compensation can require manual review" }
        require(failureCode.matches(FAILURE_CODE)) { "Manual review failure code is invalid" }
        requireChronology(occurredAt)
        state = SupportCompensationRequestState.MANUAL_REVIEW
        notificationFailureCode = failureCode
        version += 1
        lastChangedAt = occurredAt
    }

    private fun requireChronology(occurredAt: Instant) {
        require(!occurredAt.isBefore(lastChangedAt)) { "Compensation time cannot move backward" }
    }

    companion object {
        fun open(
            id: UUID,
            supportCaseId: UUID,
            customerId: UUID,
            incidentId: UUID,
            orderId: UUID?,
            storeId: UUID?,
            requesterActorId: UUID,
            executorActorId: UUID,
            benefitType: SupportCompensationBenefitType,
            amountKrw: Long,
            couponTemplateId: UUID?,
            policyVersionId: UUID,
            band: SupportCompensationBand,
            route: SupportActionApprovalRoute,
            verificationSessionId: UUID,
            targetVersion: Long,
            costSnapshot: SupportCompensationCostSnapshot,
            payloadDigest: String,
            evidenceDigest: String,
            actionRequestId: UUID?,
            createdAt: Instant,
        ): SupportCompensationRequest {
            require(amountKrw > 0) { "Compensation amount must be positive" }
            require(targetVersion >= 0) { "Compensation target version cannot be negative" }
            require(payloadDigest.matches(SHA_256) && evidenceDigest.matches(SHA_256)) { "Compensation digest is invalid" }
            require((benefitType == SupportCompensationBenefitType.COUPON) == (couponTemplateId != null)) {
                "Coupon compensation must bind exactly one template"
            }
            require((route != SupportActionApprovalRoute.NONE) == (actionRequestId != null)) {
                "Approval route must bind exactly one action request"
            }
            require(
                costSnapshot.responsibility == SupportCompensationResponsibility.PLATFORM || storeId != null,
            ) { "Store cost responsibility requires a related store" }
            val initialState =
                if (route == SupportActionApprovalRoute.NONE) {
                    SupportCompensationRequestState.READY_FOR_EXECUTION
                } else {
                    SupportCompensationRequestState.AWAITING_APPROVAL
                }
            return SupportCompensationRequest(
                id,
                supportCaseId,
                customerId,
                incidentId,
                orderId,
                storeId,
                requesterActorId,
                executorActorId,
                benefitType,
                amountKrw,
                couponTemplateId,
                policyVersionId,
                band,
                route,
                verificationSessionId,
                targetVersion,
                costSnapshot,
                payloadDigest,
                evidenceDigest,
                actionRequestId,
                initialState,
                null,
                null,
                null,
                0,
                createdAt,
            )
        }

        fun reconstitute(
            id: UUID,
            supportCaseId: UUID,
            customerId: UUID,
            incidentId: UUID,
            orderId: UUID?,
            storeId: UUID?,
            requesterActorId: UUID,
            executorActorId: UUID,
            benefitType: SupportCompensationBenefitType,
            amountKrw: Long,
            couponTemplateId: UUID?,
            policyVersionId: UUID,
            band: SupportCompensationBand,
            route: SupportActionApprovalRoute,
            verificationSessionId: UUID,
            targetVersion: Long,
            costSnapshot: SupportCompensationCostSnapshot,
            payloadDigest: String,
            evidenceDigest: String,
            actionRequestId: UUID?,
            state: SupportCompensationRequestState,
            terminalBenefitId: UUID?,
            notificationDeliveryId: UUID?,
            notificationFailureCode: String?,
            version: Long,
            lastChangedAt: Instant,
        ): SupportCompensationRequest {
            require(version >= 0) { "Compensation request version is invalid" }
            require((terminalBenefitId != null) == (state in TERMINAL_BENEFIT_STATES)) {
                "Compensation terminal binding is invalid"
            }
            require((notificationDeliveryId != null) == (state == SupportCompensationRequestState.NOTIFICATION_ACCEPTED)) {
                "Compensation notification binding is invalid"
            }
            return open(
                id,
                supportCaseId,
                customerId,
                incidentId,
                orderId,
                storeId,
                requesterActorId,
                executorActorId,
                benefitType,
                amountKrw,
                couponTemplateId,
                policyVersionId,
                band,
                route,
                verificationSessionId,
                targetVersion,
                costSnapshot,
                payloadDigest,
                evidenceDigest,
                actionRequestId,
                lastChangedAt,
            ).also {
                it.state = state
                it.terminalBenefitId = terminalBenefitId
                it.notificationDeliveryId = notificationDeliveryId
                it.notificationFailureCode = notificationFailureCode
                it.version = version
                it.lastChangedAt = lastChangedAt
            }
        }

        private val SHA_256 = Regex("^[0-9a-f]{64}$")
        private val FAILURE_CODE = Regex("^[A-Z0-9_]{1,80}$")
        private val TERMINAL_BENEFIT_STATES =
            setOf(
                SupportCompensationRequestState.BENEFIT_ISSUED,
                SupportCompensationRequestState.NOTIFICATION_RETRY,
                SupportCompensationRequestState.NOTIFICATION_ACCEPTED,
                SupportCompensationRequestState.MANUAL_REVIEW,
            )
    }
}
