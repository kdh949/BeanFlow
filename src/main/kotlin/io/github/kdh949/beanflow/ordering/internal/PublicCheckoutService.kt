package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.payment.api.OneTimePaymentAmount
import io.github.kdh949.beanflow.payment.api.OneTimePaymentAttemptView
import io.github.kdh949.beanflow.payment.api.OneTimePaymentOperations
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class PublicCheckoutResponse(
    val order: CustomerOrderDetailResponse,
    val canPay: Boolean,
    val paymentId: UUID?,
    val paymentState: String?,
    val readyAttempt: PublicOneTimePaymentAttemptResponse?,
)

internal data class PublicOneTimePaymentAttemptResponse(
    val paymentId: UUID,
    val orderReference: String,
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

/** Resolves ownership before reading Payment and delegates all writes to the existing checkout transaction. */
@Service
internal class PublicCheckoutService(
    private val references: PublicOrderReferenceService,
    private val orders: CustomerOrderQueryService,
    private val payments: OneTimePaymentOperations,
    private val checkout: OneTimeCheckoutService,
    private val clock: Clock,
) {
    fun get(
        customerId: UUID,
        reference: String,
    ): PublicCheckoutResponse {
        val resolved = references.resolveCustomer(customerId, reference)
        val order = orders.detail(customerId, reference)
        val now = clock.instant()
        val payment = payments.checkout(customerId, resolved.orderId, now)
        val canPay =
            order.status == "PENDING_PAYMENT" && order.pricing.payableKrw > 0 &&
                order.reservationExpiresAt?.isAfter(now) == true && (payment == null || payment.readyAttempt != null)
        return PublicCheckoutResponse(
            order,
            canPay,
            payment?.paymentId,
            payment?.approvalState,
            if (canPay) payment?.readyAttempt?.publicResponse(resolved.reference.value) else null,
        )
    }

    fun prepare(
        customerId: UUID,
        reference: String,
        idempotencyKey: String,
    ): PublicOneTimePaymentAttemptResponse {
        val resolved = references.resolveCustomer(customerId, reference)
        return checkout.prepare(customerId, resolved.orderId, idempotencyKey).publicResponse(resolved.reference.value)
    }
}

private fun OneTimePaymentAttemptView.publicResponse(reference: String) =
    PublicOneTimePaymentAttemptResponse(
        paymentId,
        reference,
        state,
        providerOrderId,
        customerKey,
        orderName,
        amount,
        method,
        successUrl,
        failUrl,
        expiresAt,
        updatedAt,
        correlationId,
    )
