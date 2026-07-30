package io.github.kdh949.beanflow.payment.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class RefundState {
    REQUESTED,
    PROCESSING,
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
    attemptCount: Int,
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
    var attemptCount: Int = attemptCount
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
        maxAttempts: Int,
    ): RefundClaimMode {
        check(isClaimable(now)) { "Refund is not claimable" }
        check(attemptCount < maxAttempts) { "Refund attempts are exhausted" }
        val mode =
            if (providerRequestStartedAt == null) {
                providerRequestStartedAt = now
                RefundClaimMode.REQUEST
            } else {
                RefundClaimMode.LOOKUP
            }
        attemptCount++
        state = if (mode == RefundClaimMode.REQUEST) RefundState.PROCESSING else RefundState.RECONCILING
        claimToken = token
        claimUntil = now.plus(lease)
        updatedAt = now
        return mode
    }

    fun succeed(
        providerReference: String,
        now: Instant,
    ) {
        requireOwnedClaim()
        check(providerReference.isNotBlank()) { "Provider refund reference is required" }
        state = RefundState.SUCCEEDED
        succeededAmountKrw = requestedAmountKrw
        providerRefundReference = providerReference
        lastFailureCode = null
        nextAttemptAt = null
        clearClaim()
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

    fun recordUnknown(
        code: String,
        now: Instant,
        retryDelays: List<Duration>,
        maxAttempts: Int,
    ) {
        requireOwnedClaim()
        lastFailureCode = normalized(code)
        if (attemptCount >= maxAttempts) {
            state = RefundState.MANUAL_REVIEW
            nextAttemptAt = null
        } else {
            check(retryDelays.size >= maxAttempts - 1) { "Retry delay schedule is incomplete" }
            state = RefundState.UNKNOWN
            nextAttemptAt = now.plus(retryDelays[attemptCount - 1])
        }
        clearClaim()
        updatedAt = now
    }

    fun requireClaim(token: UUID) {
        check(
            (state == RefundState.PROCESSING || state == RefundState.RECONCILING) &&
                claimToken == token,
        ) {
            "Refund claim is no longer owned"
        }
    }

    fun markManualReviewAfterExpiredClaim(
        now: Instant,
        maxAttempts: Int,
    ) {
        check(state == RefundState.PROCESSING || state == RefundState.RECONCILING) {
            "Only a processing refund can exhaust its claim"
        }
        check(attemptCount >= maxAttempts) { "Refund attempts are not exhausted" }
        check(claimUntil?.let { !now.isBefore(it) } == true) { "Refund claim lease has not expired" }
        state = RefundState.MANUAL_REVIEW
        nextAttemptAt = null
        lastFailureCode = "CLAIM_LEASE_EXPIRED"
        clearClaim()
        updatedAt = now
    }

    private fun requireOwnedClaim() {
        check(
            (state == RefundState.PROCESSING || state == RefundState.RECONCILING) &&
                claimToken != null,
        ) {
            "Refund result requires an active claim"
        }
    }

    private fun isClaimable(now: Instant): Boolean =
        when (state) {
            RefundState.REQUESTED,
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
            require(requestedAmountKrw > 0) { "Refund amount must be positive" }
            require(reason.isNotBlank()) { "Refund reason is required" }
            require(providerIdempotencyKey.isNotBlank()) { "Provider idempotency key is required" }
            require(sourceReference.isNotBlank()) { "Refund source reference is required" }
            return Refund(
                id = id,
                paymentId = paymentId,
                orderId = orderId,
                requestedAmountKrw = requestedAmountKrw,
                reason = reason,
                providerIdempotencyKey = providerIdempotencyKey,
                sourceReference = sourceReference,
                createdAt = now,
                state = RefundState.REQUESTED,
                succeededAmountKrw = null,
                providerRefundReference = null,
                attemptCount = 0,
                nextAttemptAt = now,
                providerRequestStartedAt = null,
                claimToken = null,
                claimUntil = null,
                lastFailureCode = null,
                updatedAt = now,
            )
        }

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
            attemptCount: Int,
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
                attemptCount,
                nextAttemptAt,
                providerRequestStartedAt,
                claimToken,
                claimUntil,
                lastFailureCode,
                updatedAt,
            )
    }
}
