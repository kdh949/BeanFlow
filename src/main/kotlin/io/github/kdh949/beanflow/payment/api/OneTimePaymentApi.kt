package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class PrepareOneTimePaymentCommand(
    val actorId: UUID,
    val orderId: UUID,
    val requestedAmountKrw: Long,
    val orderName: String,
    val callbackBaseUrl: String,
    val idempotencyKey: String,
    val payloadHash: String,
    val correlationId: String,
    val expiresAt: Instant,
    val now: Instant,
)

data class OneTimePaymentAmount(
    val value: Long,
    val currency: String,
)

data class OneTimePaymentAttemptView(
    val paymentId: UUID,
    val orderId: UUID,
    val state: String,
    val providerOrderId: String,
    val customerKey: String,
    val orderName: String,
    val amount: OneTimePaymentAmount,
    val method: String,
    val successUrl: String,
    val failUrl: String,
    val expiresAt: Instant,
    val updatedAt: Instant,
    val correlationId: String,
)

data class ClaimOneTimePaymentConfirmationCommand(
    val actorId: UUID,
    val paymentId: UUID,
    val providerOrderId: String,
    val paymentKey: String,
    val amountKrw: Long,
    val now: Instant,
)

enum class OneTimePaymentConfirmationClaimState {
    ACQUIRED,
    CURRENT,
}

data class OneTimePaymentConfirmationClaim(
    val state: OneTimePaymentConfirmationClaimState,
    val payment: ExternalPaymentView,
)

interface OneTimePaymentOperations {
    fun existing(command: PrepareOneTimePaymentCommand): OneTimePaymentAttemptView?

    fun prepare(command: PrepareOneTimePaymentCommand): OneTimePaymentAttemptView

    fun claimConfirmation(command: ClaimOneTimePaymentConfirmationCommand): OneTimePaymentConfirmationClaim

    fun requestProviderConfirmation(paymentId: UUID): ProviderPaymentResult

    fun current(
        actorId: UUID,
        paymentId: UUID,
    ): ExternalPaymentView
}
