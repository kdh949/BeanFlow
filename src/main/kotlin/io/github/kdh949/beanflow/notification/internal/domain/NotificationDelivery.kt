package io.github.kdh949.beanflow.notification.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class NotificationRecipientType {
    STORE,
    CUSTOMER,
}

internal enum class NotificationLogicalChannel {
    STORE_OPERATIONS,
    CUSTOMER_APP,
}

internal enum class NotificationTemplate {
    STORE_ACCEPTANCE_WARNING,
    ORDER_REJECTED,
    ORDER_READY,
    ORDER_CANCELLATION_ACCEPTED,
    CUSTOMER_CANCELLATION_REFUND_SUCCEEDED,
    CUSTOMER_CANCELLATION_REFUND_DELAYED,
    SUPPORT_PICKUP_RESCHEDULED,
    SUPPORT_POST_ACCEPTANCE_RESOLUTION,
}

internal enum class NotificationDeliveryState {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    RETRY_SCHEDULED,
    MANUAL_REVIEW,
}

internal class NotificationDelivery private constructor(
    val id: UUID,
    val eventId: UUID,
    val eventType: String,
    val logicalSource: String,
    val orderId: UUID,
    val recipientType: NotificationRecipientType,
    val recipientId: UUID,
    val logicalChannel: NotificationLogicalChannel,
    val template: NotificationTemplate,
    val payloadJson: String,
    val providerIdempotencyKey: String,
    val correlationId: String,
    val createdAt: Instant,
    state: NotificationDeliveryState,
    attemptCount: Int,
    nextAttemptAt: Instant?,
    providerDeliveryReference: String?,
    claimToken: UUID?,
    claimUntil: Instant?,
    lastFailureCode: String?,
    updatedAt: Instant,
) {
    var state: NotificationDeliveryState = state
        private set
    var attemptCount: Int = attemptCount
        private set
    var nextAttemptAt: Instant? = nextAttemptAt
        private set
    var providerDeliveryReference: String? = providerDeliveryReference
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
    ) {
        check(isClaimable(now)) { "Notification delivery is not claimable" }
        check(attemptCount < maxAttempts) { "Notification delivery attempts are exhausted" }
        attemptCount++
        state = NotificationDeliveryState.PROCESSING
        claimToken = token
        claimUntil = now.plus(lease)
        updatedAt = now
    }

    fun succeed(
        providerReference: String,
        now: Instant,
    ) {
        requireOwnedClaim()
        check(providerReference.isNotBlank()) { "Provider delivery reference is required" }
        state = NotificationDeliveryState.SUCCEEDED
        providerDeliveryReference = providerReference
        nextAttemptAt = null
        lastFailureCode = null
        clearClaim()
        updatedAt = now
    }

    fun recordFailure(
        code: String,
        now: Instant,
        retryDelays: List<Duration>,
        maxAttempts: Int,
    ) {
        requireOwnedClaim()
        lastFailureCode = normalized(code)
        if (attemptCount >= maxAttempts) {
            state = NotificationDeliveryState.MANUAL_REVIEW
            nextAttemptAt = null
        } else {
            check(retryDelays.size >= maxAttempts - 1) { "Notification retry schedule is incomplete" }
            state = NotificationDeliveryState.RETRY_SCHEDULED
            nextAttemptAt = now.plus(retryDelays[attemptCount - 1])
        }
        clearClaim()
        updatedAt = now
    }

    fun requireClaim(token: UUID) {
        check(state == NotificationDeliveryState.PROCESSING && claimToken == token) {
            "Notification delivery claim is no longer owned"
        }
    }

    fun markManualReviewAfterExpiredClaim(
        now: Instant,
        maxAttempts: Int,
    ) {
        check(state == NotificationDeliveryState.PROCESSING) {
            "Only a processing notification can exhaust its claim"
        }
        check(attemptCount >= maxAttempts) { "Notification delivery attempts are not exhausted" }
        check(claimUntil?.let { !now.isBefore(it) } == true) {
            "Notification delivery claim lease has not expired"
        }
        state = NotificationDeliveryState.MANUAL_REVIEW
        nextAttemptAt = null
        lastFailureCode = "CLAIM_LEASE_EXPIRED"
        clearClaim()
        updatedAt = now
    }

    private fun requireOwnedClaim() {
        check(state == NotificationDeliveryState.PROCESSING && claimToken != null) {
            "Notification result requires an active claim"
        }
    }

    private fun isClaimable(now: Instant): Boolean =
        when (state) {
            NotificationDeliveryState.PENDING,
            NotificationDeliveryState.RETRY_SCHEDULED,
            -> {
                nextAttemptAt?.let { !now.isBefore(it) } == true
            }

            NotificationDeliveryState.PROCESSING -> {
                claimUntil?.let { !now.isBefore(it) } == true
            }

            else -> {
                false
            }
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
        fun pending(
            id: UUID,
            eventId: UUID,
            eventType: String,
            logicalSource: String,
            orderId: UUID,
            recipientType: NotificationRecipientType,
            recipientId: UUID,
            logicalChannel: NotificationLogicalChannel,
            template: NotificationTemplate,
            payloadJson: String,
            providerIdempotencyKey: String,
            correlationId: String,
            now: Instant,
        ): NotificationDelivery {
            require(eventType.isNotBlank())
            require(logicalSource.isNotBlank())
            require(payloadJson.isNotBlank())
            require(providerIdempotencyKey.isNotBlank())
            require(correlationId.isNotBlank())
            return NotificationDelivery(
                id = id,
                eventId = eventId,
                eventType = eventType,
                logicalSource = logicalSource,
                orderId = orderId,
                recipientType = recipientType,
                recipientId = recipientId,
                logicalChannel = logicalChannel,
                template = template,
                payloadJson = payloadJson,
                providerIdempotencyKey = providerIdempotencyKey,
                correlationId = correlationId,
                createdAt = now,
                state = NotificationDeliveryState.PENDING,
                attemptCount = 0,
                nextAttemptAt = now,
                providerDeliveryReference = null,
                claimToken = null,
                claimUntil = null,
                lastFailureCode = null,
                updatedAt = now,
            )
        }

        @Suppress("LongParameterList")
        fun restore(
            id: UUID,
            eventId: UUID,
            eventType: String,
            logicalSource: String,
            orderId: UUID,
            recipientType: NotificationRecipientType,
            recipientId: UUID,
            logicalChannel: NotificationLogicalChannel,
            template: NotificationTemplate,
            payloadJson: String,
            providerIdempotencyKey: String,
            correlationId: String,
            createdAt: Instant,
            state: NotificationDeliveryState,
            attemptCount: Int,
            nextAttemptAt: Instant?,
            providerDeliveryReference: String?,
            claimToken: UUID?,
            claimUntil: Instant?,
            lastFailureCode: String?,
            updatedAt: Instant,
        ): NotificationDelivery =
            NotificationDelivery(
                id,
                eventId,
                eventType,
                logicalSource,
                orderId,
                recipientType,
                recipientId,
                logicalChannel,
                template,
                payloadJson,
                providerIdempotencyKey,
                correlationId,
                createdAt,
                state,
                attemptCount,
                nextAttemptAt,
                providerDeliveryReference,
                claimToken,
                claimUntil,
                lastFailureCode,
                updatedAt,
            )
    }
}
