package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class PostAcceptanceResolutionOutcome {
    FULL_REFUND,
    PARTIAL_REFUND,
    NO_MONETARY_RESOLUTION,
    MANUAL_SETTLEMENT_REVIEW,
}

internal enum class PostAcceptanceResolutionResponsibility {
    CUSTOMER,
    STORE,
    PLATFORM,
    SHARED,
    UNDETERMINED,
}

internal enum class PostAcceptanceResolutionState {
    PLANNED,
    EXECUTING,
    PARTIALLY_RESOLVED,
    RECONCILING,
    RESOLVED,
    MANUAL_REVIEW,
}

internal enum class PostAcceptanceResolutionStepType {
    PAYMENT_REFUND,
    POINT_RESTORATION,
    COUPON_RESTORATION,
    SETTLEMENT_ADJUSTMENT,
    CUSTOMER_NOTIFICATION,
}

internal enum class PostAcceptanceResolutionStepState {
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    NOT_REQUIRED,
    UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
    BLOCKED,
}

internal data class PostAcceptanceResolutionPlan(
    val outcome: PostAcceptanceResolutionOutcome,
    val responsibility: PostAcceptanceResolutionResponsibility,
    val cashRefundKrw: Long,
    val restorePoints: Boolean,
    val restoreCoupon: Boolean,
    val settlementAdjustmentKrw: Long?,
    val evidenceDigest: String,
) {
    init {
        require(evidenceDigest.matches(SHA_256)) { "Resolution evidence digest must be lowercase SHA-256" }
        when (outcome) {
            PostAcceptanceResolutionOutcome.FULL_REFUND,
            PostAcceptanceResolutionOutcome.PARTIAL_REFUND,
            -> require(cashRefundKrw > 0) { "Refund resolution requires a positive cash amount" }

            PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION,
            PostAcceptanceResolutionOutcome.MANUAL_SETTLEMENT_REVIEW,
            -> {
                require(cashRefundKrw == 0L) { "Non-refund resolution cannot request cash" }
                require(!restorePoints && !restoreCoupon) { "Non-refund resolution cannot restore benefits" }
            }
        }
        when (responsibility) {
            PostAcceptanceResolutionResponsibility.STORE,
            PostAcceptanceResolutionResponsibility.SHARED,
            -> {
                if (outcome != PostAcceptanceResolutionOutcome.MANUAL_SETTLEMENT_REVIEW) {
                    require(settlementAdjustmentKrw != null && settlementAdjustmentKrw < 0) {
                        "Store-bearing resolution requires an exact negative Settlement adjustment"
                    }
                }
            }

            PostAcceptanceResolutionResponsibility.CUSTOMER,
            PostAcceptanceResolutionResponsibility.PLATFORM,
            PostAcceptanceResolutionResponsibility.UNDETERMINED,
            -> require(settlementAdjustmentKrw == null) {
                "Responsibility does not permit an automatic Store Settlement adjustment"
            }
        }
    }

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

internal data class PostAcceptanceResolutionClaim(
    val stepType: PostAcceptanceResolutionStepType,
    val claimToken: UUID,
    val attemptCount: Int,
    val reconciliation: Boolean,
    val dueAt: Instant,
)

internal data class PostAcceptanceResolutionStepResult(
    val stepType: PostAcceptanceResolutionStepType,
    val state: PostAcceptanceResolutionStepState,
    val replayed: Boolean,
)

internal class PostAcceptanceResolutionStep private constructor(
    val type: PostAcceptanceResolutionStepType,
    state: PostAcceptanceResolutionStepState,
    attemptCount: Int,
    nextAttemptAt: Instant?,
    resultReference: String?,
    failureCode: String?,
    claimToken: UUID?,
    claimUntil: Instant?,
    updatedAt: Instant,
) {
    var state: PostAcceptanceResolutionStepState = state
        private set
    var attemptCount: Int = attemptCount
        private set
    var nextAttemptAt: Instant? = nextAttemptAt
        private set
    var resultReference: String? = resultReference
        private set
    var failureCode: String? = failureCode
        private set
    var claimToken: UUID? = claimToken
        private set
    var claimUntil: Instant? = claimUntil
        private set
    var updatedAt: Instant = updatedAt
        private set

    fun claim(
        token: UUID,
        now: Instant,
        lease: Duration,
    ): PostAcceptanceResolutionClaim {
        require(!lease.isZero && !lease.isNegative) { "Resolution claim lease must be positive" }
        val dueAt = nextAttemptAt ?: updatedAt
        val reconciliation = state == PostAcceptanceResolutionStepState.UNKNOWN
        check(
            state == PostAcceptanceResolutionStepState.PENDING ||
                state == PostAcceptanceResolutionStepState.RETRY_SCHEDULED || reconciliation,
        ) { "Resolution step is not claimable" }
        check(!now.isBefore(dueAt)) { "Resolution step is not due" }
        attemptCount += 1
        state =
            if (reconciliation) {
                PostAcceptanceResolutionStepState.RECONCILING
            } else {
                PostAcceptanceResolutionStepState.PROCESSING
            }
        claimToken = token
        claimUntil = now.plus(lease)
        nextAttemptAt = null
        updatedAt = now
        return PostAcceptanceResolutionClaim(type, token, attemptCount, reconciliation, dueAt)
    }

    fun recordSuccess(
        token: UUID,
        reference: String,
        now: Instant,
    ): PostAcceptanceResolutionStepResult {
        val normalized = reference.normalizedReference()
        if (state == PostAcceptanceResolutionStepState.SUCCEEDED) {
            check(resultReference == normalized) { "Resolution step result conflicts with its terminal result" }
            return PostAcceptanceResolutionStepResult(type, state, replayed = true)
        }
        requireClaim(token)
        state = PostAcceptanceResolutionStepState.SUCCEEDED
        resultReference = normalized
        failureCode = null
        nextAttemptAt = null
        clearClaim()
        updatedAt = now
        return PostAcceptanceResolutionStepResult(type, state, replayed = false)
    }

    fun recordUnknown(
        token: UUID,
        code: String,
        now: Instant,
        retryAt: Instant,
    ) {
        requireClaim(token)
        require(retryAt.isAfter(now)) { "Resolution reconciliation retry must be in the future" }
        state = PostAcceptanceResolutionStepState.UNKNOWN
        failureCode = code.normalizedFailureCode()
        nextAttemptAt = retryAt
        clearClaim()
        updatedAt = now
    }

    fun recordManualReview(
        token: UUID,
        code: String,
        now: Instant,
    ) {
        requireClaim(token)
        state = PostAcceptanceResolutionStepState.MANUAL_REVIEW
        failureCode = code.normalizedFailureCode()
        nextAttemptAt = null
        clearClaim()
        updatedAt = now
    }

    fun recoverExpiredClaim(now: Instant) {
        check(
            state == PostAcceptanceResolutionStepState.PROCESSING ||
                state == PostAcceptanceResolutionStepState.RECONCILING,
        ) { "Only a processing Resolution step can recover a claim" }
        check(claimUntil?.let { !now.isBefore(it) } == true) { "Resolution claim lease has not expired" }
        state = PostAcceptanceResolutionStepState.UNKNOWN
        failureCode = "CLAIM_LEASE_EXPIRED"
        nextAttemptAt = now
        clearClaim()
        updatedAt = now
    }

    private fun requireClaim(token: UUID) {
        check(
            (state == PostAcceptanceResolutionStepState.PROCESSING ||
                state == PostAcceptanceResolutionStepState.RECONCILING) && claimToken == token,
        ) { "Resolution step result requires its active claim" }
    }

    private fun clearClaim() {
        claimToken = null
        claimUntil = null
    }

    companion object {
        fun initial(
            type: PostAcceptanceResolutionStepType,
            state: PostAcceptanceResolutionStepState,
            now: Instant,
        ): PostAcceptanceResolutionStep {
            require(
                state == PostAcceptanceResolutionStepState.PENDING ||
                    state == PostAcceptanceResolutionStepState.NOT_REQUIRED ||
                    state == PostAcceptanceResolutionStepState.MANUAL_REVIEW ||
                    state == PostAcceptanceResolutionStepState.BLOCKED,
            ) { "Resolution step initial state is invalid" }
            return PostAcceptanceResolutionStep(type, state, 0, now.takeIf { state == PostAcceptanceResolutionStepState.PENDING }, null, null, null, null, now)
        }

        fun restore(
            type: PostAcceptanceResolutionStepType,
            state: PostAcceptanceResolutionStepState,
            attemptCount: Int,
            nextAttemptAt: Instant?,
            resultReference: String?,
            failureCode: String?,
            claimToken: UUID?,
            claimUntil: Instant?,
            updatedAt: Instant,
        ): PostAcceptanceResolutionStep =
            PostAcceptanceResolutionStep(
                type,
                state,
                attemptCount,
                nextAttemptAt,
                resultReference,
                failureCode,
                claimToken,
                claimUntil,
                updatedAt,
            )
    }
}

internal class PostAcceptanceResolutionCase private constructor(
    val id: UUID,
    val supportCaseId: UUID,
    val supportActionRequestId: UUID,
    val supportActionRevisionId: UUID,
    val revisionNumber: Int,
    val actionPayloadDigest: String,
    val orderId: UUID,
    val triggerOrderState: String,
    val triggerOrderVersion: Long,
    val requesterActorId: UUID,
    val executorActorId: UUID,
    val plan: PostAcceptanceResolutionPlan,
    val createdAt: Instant,
    state: PostAcceptanceResolutionState,
    steps: Collection<PostAcceptanceResolutionStep>,
) {
    var state: PostAcceptanceResolutionState = state
        private set
    private val stepsByType = steps.associateBy(PostAcceptanceResolutionStep::type)

    init {
        require(triggerOrderState in POST_ACCEPTANCE_STATES) { "Resolution requires a post-acceptance Order fact" }
        require(triggerOrderVersion >= 0) { "Resolution Order version cannot be negative" }
        require(revisionNumber > 0) { "Resolution revision number must be positive" }
        require(actionPayloadDigest.matches(SHA_256)) { "Resolution action digest must be lowercase SHA-256" }
        require(stepsByType.keys == PostAcceptanceResolutionStepType.entries.toSet()) {
            "Resolution must contain exactly one step of every type"
        }
    }

    fun step(type: PostAcceptanceResolutionStepType): PostAcceptanceResolutionStep = requireNotNull(stepsByType[type])

    fun claim(
        type: PostAcceptanceResolutionStepType,
        token: UUID,
        now: Instant,
        lease: Duration,
    ): PostAcceptanceResolutionClaim =
        step(type).claim(token, now, lease).also { recalculate() }

    fun recordSuccess(
        type: PostAcceptanceResolutionStepType,
        token: UUID,
        reference: String,
        now: Instant,
    ): PostAcceptanceResolutionStepResult =
        step(type).recordSuccess(token, reference, now).also { recalculate() }

    fun recordUnknown(
        type: PostAcceptanceResolutionStepType,
        token: UUID,
        code: String,
        now: Instant,
        retryAt: Instant,
    ) {
        step(type).recordUnknown(token, code, now, retryAt)
        recalculate()
    }

    fun recordManualReview(
        type: PostAcceptanceResolutionStepType,
        token: UUID,
        code: String,
        now: Instant,
    ) {
        step(type).recordManualReview(token, code, now)
        recalculate()
    }

    fun recoverExpiredClaim(
        type: PostAcceptanceResolutionStepType,
        now: Instant,
    ) {
        step(type).recoverExpiredClaim(now)
        recalculate()
    }

    fun isFinanciallyResolved(): Boolean =
        financialSteps().all { it.state == PostAcceptanceResolutionStepState.SUCCEEDED || it.state == PostAcceptanceResolutionStepState.NOT_REQUIRED }

    private fun recalculate() {
        val financial = financialSteps()
        state =
            when {
                financial.all { it.state == PostAcceptanceResolutionStepState.SUCCEEDED || it.state == PostAcceptanceResolutionStepState.NOT_REQUIRED } ->
                    PostAcceptanceResolutionState.RESOLVED
                financial.any { it.state == PostAcceptanceResolutionStepState.UNKNOWN || it.state == PostAcceptanceResolutionStepState.RECONCILING } ->
                    PostAcceptanceResolutionState.RECONCILING
                financial.none { it.state in ACTIONABLE_STATES } &&
                    financial.any { it.state == PostAcceptanceResolutionStepState.SUCCEEDED } ->
                    PostAcceptanceResolutionState.PARTIALLY_RESOLVED
                financial.none { it.state in ACTIONABLE_STATES } ->
                    PostAcceptanceResolutionState.MANUAL_REVIEW
                else -> PostAcceptanceResolutionState.EXECUTING
            }
    }

    private fun financialSteps(): List<PostAcceptanceResolutionStep> =
        stepsByType.values.filter { it.type != PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION }

    companion object {
        fun plan(
            id: UUID,
            supportCaseId: UUID,
            supportActionRequestId: UUID,
            supportActionRevisionId: UUID,
            revisionNumber: Int,
            actionPayloadDigest: String,
            orderId: UUID,
            triggerOrderState: String,
            triggerOrderVersion: Long,
            requesterActorId: UUID,
            executorActorId: UUID,
            plan: PostAcceptanceResolutionPlan,
            createdAt: Instant,
        ): PostAcceptanceResolutionCase =
            PostAcceptanceResolutionCase(
                id,
                supportCaseId,
                supportActionRequestId,
                supportActionRevisionId,
                revisionNumber,
                actionPayloadDigest,
                orderId,
                triggerOrderState,
                triggerOrderVersion,
                requesterActorId,
                executorActorId,
                plan,
                createdAt,
                PostAcceptanceResolutionState.PLANNED,
                initialSteps(plan, createdAt),
            )

        fun restore(
            id: UUID,
            supportCaseId: UUID,
            supportActionRequestId: UUID,
            supportActionRevisionId: UUID,
            revisionNumber: Int,
            actionPayloadDigest: String,
            orderId: UUID,
            triggerOrderState: String,
            triggerOrderVersion: Long,
            requesterActorId: UUID,
            executorActorId: UUID,
            plan: PostAcceptanceResolutionPlan,
            createdAt: Instant,
            state: PostAcceptanceResolutionState,
            steps: Collection<PostAcceptanceResolutionStep>,
        ): PostAcceptanceResolutionCase =
            PostAcceptanceResolutionCase(
                id,
                supportCaseId,
                supportActionRequestId,
                supportActionRevisionId,
                revisionNumber,
                actionPayloadDigest,
                orderId,
                triggerOrderState,
                triggerOrderVersion,
                requesterActorId,
                executorActorId,
                plan,
                createdAt,
                state,
                steps,
            )

        private fun initialSteps(
            plan: PostAcceptanceResolutionPlan,
            now: Instant,
        ): List<PostAcceptanceResolutionStep> =
            PostAcceptanceResolutionStepType.entries.map { type ->
                PostAcceptanceResolutionStep.initial(type, initialState(plan, type), now)
            }

        private fun initialState(
            plan: PostAcceptanceResolutionPlan,
            type: PostAcceptanceResolutionStepType,
        ): PostAcceptanceResolutionStepState =
            when (type) {
                PostAcceptanceResolutionStepType.PAYMENT_REFUND ->
                    required(plan.outcome.isRefund())
                PostAcceptanceResolutionStepType.POINT_RESTORATION -> required(plan.restorePoints)
                PostAcceptanceResolutionStepType.COUPON_RESTORATION -> required(plan.restoreCoupon)
                PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION -> PostAcceptanceResolutionStepState.PENDING
                PostAcceptanceResolutionStepType.SETTLEMENT_ADJUSTMENT ->
                    when {
                        plan.outcome == PostAcceptanceResolutionOutcome.MANUAL_SETTLEMENT_REVIEW ->
                            PostAcceptanceResolutionStepState.MANUAL_REVIEW
                        plan.responsibility == PostAcceptanceResolutionResponsibility.UNDETERMINED ->
                            PostAcceptanceResolutionStepState.BLOCKED
                        plan.responsibility == PostAcceptanceResolutionResponsibility.STORE ||
                            plan.responsibility == PostAcceptanceResolutionResponsibility.SHARED ->
                            PostAcceptanceResolutionStepState.PENDING
                        else -> PostAcceptanceResolutionStepState.NOT_REQUIRED
                    }
            }

        private fun required(required: Boolean): PostAcceptanceResolutionStepState =
            if (required) PostAcceptanceResolutionStepState.PENDING else PostAcceptanceResolutionStepState.NOT_REQUIRED

        private val POST_ACCEPTANCE_STATES = setOf("PREPARING", "READY", "COMPLETED")
        private val ACTIONABLE_STATES =
            setOf(
                PostAcceptanceResolutionStepState.PENDING,
                PostAcceptanceResolutionStepState.PROCESSING,
                PostAcceptanceResolutionStepState.RETRY_SCHEDULED,
            )
        private val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun PostAcceptanceResolutionOutcome.isRefund(): Boolean =
    this == PostAcceptanceResolutionOutcome.FULL_REFUND || this == PostAcceptanceResolutionOutcome.PARTIAL_REFUND

private fun String.normalizedReference(): String =
    trim().also {
        require(it == this && it.length in 1..240 && it.none(Char::isISOControl)) {
            "Resolution result reference is invalid"
        }
    }

private fun String.normalizedFailureCode(): String =
    trim().uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(80).ifBlank { "UNKNOWN" }
