package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.ClaimOneTimePaymentConfirmationCommand
import io.github.kdh949.beanflow.payment.api.OneTimePaymentAttemptView
import io.github.kdh949.beanflow.payment.api.OneTimePaymentConfirmationClaimState
import io.github.kdh949.beanflow.payment.api.OneTimePaymentOperations
import io.github.kdh949.beanflow.payment.api.PrepareOneTimePaymentCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

internal data class OneTimePaymentConfirmationRequest(
    val paymentKey: String,
    val orderId: String,
    val amount: Long,
)

@Service
internal class OneTimeCheckoutService(
    private val preparation: OneTimePaymentPreparationTransaction,
    private val payments: OneTimePaymentOperations,
    private val resultTransaction: PaymentResultTransaction,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val correlationIdSource: CorrelationIdSource,
    private val clock: Clock,
    @Value("\${beanflow.checkout.frontend-base-url:http://localhost:5173}")
    private val frontendBaseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun prepare(
        customerId: UUID,
        orderId: UUID,
        idempotencyKey: String,
    ): OneTimePaymentAttemptView =
        preparation.prepare(
            customerId = customerId,
            orderId = orderId,
            idempotencyKey = idempotencyKey,
            callbackBaseUrl = frontendBaseUrl,
            correlationId = correlationIdSource.currentOrCreate(),
            now = clock.instant(),
        )

    fun confirm(
        customerId: UUID,
        paymentId: UUID,
        idempotencyKey: String,
        request: OneTimePaymentConfirmationRequest,
    ): StoredHttpResponse {
        if (idempotencyKey.length !in 8..128) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Confirmation Idempotency-Key is invalid")
        }
        val now = clock.instant()
        val claim =
            payments.claimConfirmation(
                ClaimOneTimePaymentConfirmationCommand(
                    actorId = customerId,
                    paymentId = paymentId,
                    providerOrderId = request.orderId,
                    paymentKey = request.paymentKey,
                    amountKrw = request.amount,
                    now = now,
                ),
            )
        if (claim.state == OneTimePaymentConfirmationClaimState.CURRENT) {
            return responseFactory.current(claim.payment, replay = true)
        }

        val result =
            try {
                payments.requestProviderConfirmation(paymentId)
            } catch (failure: ProviderTransportFailure) {
                logger.warn("payment_confirm paymentId={} outcome=UNKNOWN reason=PROVIDER_CALL_FAILED", paymentId)
                ProviderPaymentResult.Unknown("PROVIDER_CALL_FAILED")
            } catch (failure: DataAccessException) {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Payment confirmation request could not be prepared",
                )
            }
        return try {
            resultTransaction.apply(customerId, claim.payment.orderId, paymentId, result, clock.instant())
        } catch (failure: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Payment confirmation result could not be committed and will be reconciled",
            )
        }
    }

    fun current(
        customerId: UUID,
        paymentId: UUID,
    ): StoredHttpResponse = responseFactory.current(payments.current(customerId, paymentId), replay = false)
}

@Service
internal class OneTimePaymentPreparationTransaction(
    private val orders: OrderJpaRepository,
    private val orderLines: OrderLineJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val payments: OneTimePaymentOperations,
) {
    @Transactional
    fun prepare(
        customerId: UUID,
        orderId: UUID,
        idempotencyKey: String,
        callbackBaseUrl: String,
        correlationId: String,
        now: java.time.Instant,
    ): OneTimePaymentAttemptView {
        val order =
            orders.findLockedById(orderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        val deadline =
            order.reservationExpiresAt
                ?: throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order has no payment reservation deadline")
        val command =
            PrepareOneTimePaymentCommand(
                actorId = customerId,
                orderId = orderId,
                requestedAmountKrw = order.payableKrw,
                orderName = orderName(orderId),
                callbackBaseUrl = callbackBaseUrl,
                idempotencyKey = idempotencyKey,
                payloadHash = sha256(orderId.toString().lowercase()),
                correlationId = correlationId,
                expiresAt = deadline,
                now = now,
            )
        payments.existing(command)?.let { return it }
        if (order.state == OrderState.PENDING_PAYMENT && !now.isBefore(deadline)) {
            expiryUseCase.expireIfDue(orderId, now)
            throw DomainFailure(FailureCode.RESERVATION_EXPIRED, "Order reservation lease has expired")
        }
        if (order.state != OrderState.PENDING_PAYMENT || order.payableKrw <= 0) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for one-time payment")
        }
        return payments.prepare(command)
    }

    private fun orderName(orderId: UUID): String {
        val lines = orderLines.findAllByOrderIdOrderByLineSequence(orderId)
        val first =
            lines
                .firstOrNull()
                ?.menuName
                ?.trim()
                .orEmpty()
        if (first.isEmpty()) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order line snapshot is missing")
        }
        val quantity = lines.sumOf { it.quantity }
        return if (quantity <= 1) first.take(100) else "$first 외 ${quantity - 1}잔".take(100)
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
