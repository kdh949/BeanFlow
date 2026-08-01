package io.github.kdh949.beanflow.payment.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class RefundState {
    REQUESTED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
}

internal enum class RefundClaimMode {
    REQUEST,
    LOOKUP,
}

internal class Refund private constructor(
    val id: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val requestedAmountKrw: Long,
    val reason: String,
    val providerIdempotencyKey: String,
    val sourceReference: String,
    val createdAt: Instant,
    state: RefundState,
    succeededAmountKrw: Long?,
    providerRefundReference: String?,
    requestAttemptCount: Int,
    lookupAttemptCount: Int,
    nextAction: RefundClaimMode,
    nextAttemptAt: Instant?,
    providerRequestStartedAt: Instant?,
    claimToken: UUID?,
    claimUntil: Instant?,
    lastFailureCode: String?,
    updatedAt: Instant,
) {
    var state: RefundState = state
        private set
    var succeededAmountKrw: Long? = succeededAmountKrw
        private set
    var providerRefundReference: String? = providerRefundReference
        private set
    var requestAttemptCount: Int = requestAttemptCount
        private set
    var lookupAttemptCount: Int = lookupAttemptCount
        private set
    val attemptCount: Int
        get() = requestAttemptCount + lookupAttemptCount
    var nextAction: RefundClaimMode = nextAction
        private set
    var nextAttemptAt: Instant? = nextAttemptAt
        private set
    var providerRequestStartedAt: Instant? = providerRequestStartedAt
        private set
    var claimToken: UUID? = claimToken
        private set
    var claimUntil: Instant? = claimUntil
        private set
    var lastFailureCode: String? = lastFailureCode
        private set
    var updatedAt: Instant = updatedAt
        private set

    fun claim(
        token: UUID,
        now: Instant,
        lease: Duration,
        requestMaxAttempts: Int = REQUEST_MAX_ATTEMPTS,
        lookupMaxAttempts: Int = LOOKUP_MAX_ATTEMPTS,
    ): RefundClaimMode {
        check(isClaimable(now)) { "Refund is not claimable" }
        val mode = nextAction
        when (mode) {
            RefundClaimMode.REQUEST -> {
                check(requestAttemptCount < requestMaxAttempts) { "Refund request attempts are exhausted" }
                requestAttemptCount++
                providerRequestStartedAt = providerRequestStartedAt ?: now
                state = RefundState.PROCESSING
            }

            RefundClaimMode.LOOKUP -> {
                check(lookupAttemptCount < lookupMaxAttempts) { "Refund lookup attempts are exhausted" }
                lookupAttemptCount++
                state = RefundState.RECONCILING
            }
        }
        claimToken = token
        claimUntil = now.plus(lease)
        updatedAt = now
        return mode
    }

    fun succeed(
        providerReference: String?,
        now: Instant,
    ) {
        requireOwnedClaim()
        if (requestedAmountKrw > 0) {
            check(!providerReference.isNullOrBlank()) { "Provider refund reference is required" }
        }
        state = RefundState.SUCCEEDED
        succeededAmountKrw = requestedAmountKrw
        providerRefundReference = providerReference
        lastFailureCode = null
        nextAttemptAt = null
        clearClaim()
        updatedAt = now
    }

    fun succeedWithoutProvider(now: Instant) {
        check(requestedAmountKrw == 0L) { "Only a zero-cash Refund can skip Provider" }
        check(state == RefundState.REQUESTED) { "Refund is not awaiting zero-cash completion" }
        state = RefundState.SUCCEEDED
        succeededAmountKrw = 0
        providerRefundReference = null
        nextAttemptAt = null
        updatedAt = now
    }

    fun fail(
        code: String,
        now: Instant,
    ) {
        requireOwnedClaim()
        state = RefundState.FAILED
        lastFailureCode = normalized(code)
        nextAttemptAt = null
        clearClaim()
        updatedAt = now
    }

    fun recordRetryableRequestFailure(
        code: String,
        now: Instant,
        retryDelays: List<Duration> = REQUEST_RETRY_DELAYS,
        maxAttempts: Int = REQUEST_MAX_ATTEMPTS,
    ) {
        requireOwnedClaim(RefundClaimMode.REQUEST)
        lastFailureCode = normalized(code)
        if (requestAttemptCount >= maxAttempts) {
            state = RefundState.MANUAL_REVIEW
            nextAttemptAt = null
        } else {
            check(retryDelays.size >= maxAttempts - 1) { "Request retry schedule is incomplete" }
            state = RefundState.RETRY_SCHEDULED
            nextAction = RefundClaimMode.REQUEST
            nextAttemptAt = now.plus(retryDelays[requestAttemptCount - 1])
        }
        clearClaim()
        updatedAt = now
    }

    fun recordUnknown(
        code: String,
        now: Instant,
        lookupDelays: List<Duration> = LOOKUP_RETRY_DELAYS,
        lookupMaxAttempts: Int = LOOKUP_MAX_ATTEMPTS,
    ) {
        requireOwnedClaim()
        lastFailureCode = normalized(code)
        nextAction = RefundClaimMode.LOOKUP
        if (lookupAttemptCount >= lookupMaxAttempts) {
            state = RefundState.MANUAL_REVIEW
            nextAttemptAt = null
        } else {
            check(lookupDelays.size >= lookupMaxAttempts) { "Lookup retry schedule is incomplete" }
            state = RefundState.UNKNOWN
            nextAttemptAt = now.plus(lookupDelays[lookupAttemptCount])
        }
        clearClaim()
        updatedAt = now
    }

    fun requireClaim(token: UUID) {
        check(
            (state == RefundState.PROCESSING || state == RefundState.RECONCILING) && claimToken == token,
        ) { "Refund claim is no longer owned" }
    }

    fun recoverExpiredClaim(
        now: Instant,
        lookupMaxAttempts: Int = LOOKUP_MAX_ATTEMPTS,
    ) {
        check(state == RefundState.PROCESSING || state == RefundState.RECONCILING) {
            "Only a processing Refund can recover an expired claim"
        }
        check(claimUntil?.let { !now.isBefore(it) } == true) { "Refund claim lease has not expired" }
        nextAction = RefundClaimMode.LOOKUP
        if (lookupAttemptCount >= lookupMaxAttempts) {
            state = RefundState.MANUAL_REVIEW
            nextAttemptAt = null
        } else {
            state = RefundState.UNKNOWN
            nextAttemptAt = now
        }
        lastFailureCode = "CLAIM_LEASE_EXPIRED"
        clearClaim()
        updatedAt = now
    }

    private fun requireOwnedClaim(expected: RefundClaimMode? = null) {
        check(
            (state == RefundState.PROCESSING || state == RefundState.RECONCILING) && claimToken != null,
        ) { "Refund result requires an active claim" }
        if (expected != null) check(nextAction == expected) { "Refund result mode does not match its claim" }
    }

    private fun isClaimable(now: Instant): Boolean =
        when (state) {
            RefundState.REQUESTED,
            RefundState.RETRY_SCHEDULED,
            RefundState.UNKNOWN,
            -> nextAttemptAt?.let { !now.isBefore(it) } == true

            RefundState.PROCESSING,
            RefundState.RECONCILING,
            -> claimUntil?.let { !now.isBefore(it) } == true

            else -> false
        }

    private fun clearClaim() {
        claimToken = null
        claimUntil = null
    }

    private fun normalized(code: String): String =
        code
            .trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .take(80)
            .ifBlank { "UNKNOWN" }

    companion object {
        const val REQUEST_MAX_ATTEMPTS = 3
        const val LOOKUP_MAX_ATTEMPTS = 5
        val REQUEST_RETRY_DELAYS: List<Duration> = listOf(Duration.ofSeconds(10), Duration.ofSeconds(30))
        val LOOKUP_RETRY_DELAYS: List<Duration> =
            listOf(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
            )

        fun request(
            id: UUID,
            paymentId: UUID,
            orderId: UUID,
            requestedAmountKrw: Long,
            reason: String,
            providerIdempotencyKey: String,
            sourceReference: String,
            now: Instant,
        ): Refund {
            require(requestedAmountKrw >= 0) { "Refund amount must not be negative" }
            require(reason.isNotBlank()) { "Refund reason is required" }
            require(providerIdempotencyKey.isNotBlank()) { "Provider idempotency key is required" }
            require(sourceReference.isNotBlank()) { "Refund source reference is required" }
            return Refund(
                id,
                paymentId,
                orderId,
                requestedAmountKrw,
                reason,
                providerIdempotencyKey,
                sourceReference,
                now,
                RefundState.REQUESTED,
                null,
                null,
                0,
                0,
                RefundClaimMode.REQUEST,
                now,
                null,
                null,
                null,
                null,
                now,
            )
        }

        @Suppress("LongParameterList")
        fun restore(
            id: UUID,
            paymentId: UUID,
            orderId: UUID,
            requestedAmountKrw: Long,
            reason: String,
            providerIdempotencyKey: String,
            sourceReference: String,
            createdAt: Instant,
            state: RefundState,
            succeededAmountKrw: Long?,
            providerRefundReference: String?,
            requestAttemptCount: Int,
            lookupAttemptCount: Int,
            nextAction: RefundClaimMode,
            nextAttemptAt: Instant?,
            providerRequestStartedAt: Instant?,
            claimToken: UUID?,
            claimUntil: Instant?,
            lastFailureCode: String?,
            updatedAt: Instant,
        ): Refund =
            Refund(
                id,
                paymentId,
                orderId,
                requestedAmountKrw,
                reason,
                providerIdempotencyKey,
                sourceReference,
                createdAt,
                state,
                succeededAmountKrw,
                providerRefundReference,
                requestAttemptCount,
                lookupAttemptCount,
                nextAction,
                nextAttemptAt,
                providerRequestStartedAt,
                claimToken,
                claimUntil,
                lastFailureCode,
                updatedAt,
            )
    }
}
