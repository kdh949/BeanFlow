package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityOperations
import io.github.kdh949.beanflow.merchant.api.StoreOrderAvailabilityReason
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode
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
import org.springframework.transaction.TransactionException
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

internal sealed interface OneTimePaymentPreparationResult {
    data class Ready(
        val attempt: OneTimePaymentAttemptView,
    ) : OneTimePaymentPreparationResult

    data object Expired : OneTimePaymentPreparationResult

    data object StoreClosed : OneTimePaymentPreparationResult
}

@Service
internal class OneTimeCheckoutService(
    private val preparation: OneTimePaymentPreparationTransaction,
    private val payments: OneTimePaymentOperations,
    private val resultTransaction: PaymentResultTransaction,
    private val commitmentRecovery: PaymentCommitmentRecoveryTransaction,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val orderReferenceProjection: PaymentOrderReferenceProjection,
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
    ): OneTimePaymentAttemptView {
        val result =
            preparation.prepare(
                customerId = customerId,
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                callbackBaseUrl = frontendBaseUrl,
                correlationId = correlationIdSource.currentOrCreate(),
                now = clock.instant(),
            )
        return when (result) {
            is OneTimePaymentPreparationResult.Ready -> {
                result.attempt
            }

            OneTimePaymentPreparationResult.Expired -> {
                throw DomainFailure(FailureCode.RESERVATION_EXPIRED, "Order reservation lease has expired")
            }

            OneTimePaymentPreparationResult.StoreClosed -> {
                throw DomainFailure(FailureCode.STORE_CLOSED, "Store ordering window has closed")
            }
        }
    }

    fun confirm(
        customerId: UUID,
        paymentId: UUID,
        idempotencyKey: String,
        request: OneTimePaymentConfirmationRequest,
    ): StoredHttpResponse {
        if (idempotencyKey.length !in 8..128) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Confirmation Idempotency-Key is invalid")
        }
        val requestedAt = clock.instant()
        val current = payments.current(customerId, paymentId)
        val unguardedCommand =
            ClaimOneTimePaymentConfirmationCommand(
                actorId = customerId,
                paymentId = paymentId,
                providerOrderId = request.orderId,
                paymentKey = request.paymentKey,
                amountKrw = request.amount,
                now = requestedAt,
            )
        payments.replayClaimedConfirmation(unguardedCommand)?.let {
            return customerResponse(customerId, it.payment, replay = true)
        }
        val gate =
            try {
                preparation.immediateConfirmationGate(customerId, current.orderId, requestedAt)
            } catch (failure: DomainFailure) {
                payments.replayClaimedConfirmation(unguardedCommand)?.let {
                    return customerResponse(customerId, it.payment, replay = true)
                }
                throw failure
            }
        val claim =
            payments.claimConfirmation(
                unguardedCommand.copy(
                    now = gate.checkedAt,
                    providerConfirmationDeadline = gate.deadline,
                ),
            )
        if (claim.state == OneTimePaymentConfirmationClaimState.CURRENT) {
            return customerResponse(customerId, claim.payment, replay = true)
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
        val appliedAt = clock.instant()
        return try {
            resultTransaction.apply(customerId, claim.payment.orderId, paymentId, result, appliedAt)
        } catch (failure: ImmediatePaymentCommitmentFailure) {
            recoverApproved(customerId, claim.payment.orderId, paymentId, result, failure.failureCode.name, appliedAt)
        } catch (failure: DataAccessException) {
            recoverApproved(customerId, claim.payment.orderId, paymentId, result, FailureCode.DEPENDENCY_UNAVAILABLE.name, appliedAt)
        } catch (failure: TransactionException) {
            recoverApproved(customerId, claim.payment.orderId, paymentId, result, FailureCode.DEPENDENCY_UNAVAILABLE.name, appliedAt)
        }
    }

    fun current(
        customerId: UUID,
        paymentId: UUID,
    ): StoredHttpResponse = customerResponse(customerId, payments.current(customerId, paymentId), replay = false)

    private fun recoverApproved(
        customerId: UUID,
        orderId: UUID,
        paymentId: UUID,
        result: ProviderPaymentResult,
        failureCode: String,
        now: java.time.Instant,
    ): StoredHttpResponse {
        val approved =
            result as? ProviderPaymentResult.Approved
                ?: throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Payment confirmation result could not be committed and will be reconciled",
                )
        return try {
            commitmentRecovery.recoverApproved(customerId, orderId, paymentId, approved, failureCode, now)
        } catch (failure: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Approved payment recovery could not be committed and will be retried by reconciliation",
            )
        } catch (failure: TransactionException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Approved payment recovery could not be committed and will be retried by reconciliation",
            )
        }
    }

    private fun customerResponse(
        customerId: UUID,
        payment: io.github.kdh949.beanflow.payment.api.ExternalPaymentView,
        replay: Boolean,
    ): StoredHttpResponse =
        responseFactory.current(
            payment,
            orderReferenceProjection.resolveOwned(customerId, payment.orderId),
            replay,
        )
}

internal data class OneTimeConfirmationGate(
    val deadline: java.time.Instant?,
    val checkedAt: java.time.Instant,
)

@Service
internal class OneTimePaymentPreparationTransaction(
    private val orders: OrderJpaRepository,
    private val orderLines: OrderLineJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val payments: OneTimePaymentOperations,
    private val availabilityOperations: StoreOrderAvailabilityOperations,
    private val clock: Clock,
) {
    @Transactional
    fun immediateConfirmationGate(
        customerId: UUID,
        orderId: UUID,
        requestedAt: java.time.Instant,
    ): OneTimeConfirmationGate {
        val order =
            orders.findById(orderId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        if (order.checkoutMode != CheckoutMode.IMMEDIATE) return OneTimeConfirmationGate(null, requestedAt)
        var checkedAt = requestedAt
        var deadline = order.orderingWindowClosesAt
        if (order.state == OrderState.PENDING_PAYMENT) {
            availabilityOperations.lockForOrderCommitment(order.storeId, requestedAt)
            checkedAt = clock.instant()
            val availability = availabilityOperations.inspect(order.storeId, checkedAt)
            if (!availability.available) {
                val code =
                    when (availability.reason) {
                        StoreOrderAvailabilityReason.AVAILABLE -> FailureCode.DEPENDENCY_UNAVAILABLE
                        StoreOrderAvailabilityReason.STORE_HOURS_NOT_CONFIGURED -> FailureCode.STORE_HOURS_NOT_CONFIGURED
                        StoreOrderAvailabilityReason.STORE_CLOSED -> FailureCode.STORE_CLOSED
                        StoreOrderAvailabilityReason.STORE_NOT_ACCEPTING_ORDERS -> FailureCode.STORE_NOT_ACCEPTING_ORDERS
                }
                throw DomainFailure(code, "Store is not available for immediate payment confirmation")
            }
            deadline =
                minOf(
                    requireNotNull(deadline) { "Immediate order cutoff is missing" },
                    requireNotNull(availability.orderingWindowClosesAt) { "Available Store window has no close" },
                )
        }
        return OneTimeConfirmationGate(deadline, checkedAt)
    }

    @Transactional
    fun prepare(
        customerId: UUID,
        orderId: UUID,
        idempotencyKey: String,
        callbackBaseUrl: String,
        correlationId: String,
        now: java.time.Instant,
    ): OneTimePaymentPreparationResult {
        val order =
            orders.findLockedById(orderId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
        if (order.customerId != customerId) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
        }
        val deadline =
            when (order.checkoutMode) {
                io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode.LEGACY_RESERVED -> {
                    order.reservationExpiresAt
                        ?: throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order has no payment reservation deadline")
                }

                io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode.IMMEDIATE -> {
                    order.orderingWindowClosesAt
                        ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Immediate order cutoff is missing")
                }
            }
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
        if (order.state == OrderState.PENDING_PAYMENT && !now.isBefore(deadline)) {
            return if (order.checkoutMode == io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode.LEGACY_RESERVED) {
                expiryUseCase.expireIfDue(orderId, now)
                OneTimePaymentPreparationResult.Expired
            } else {
                OneTimePaymentPreparationResult.StoreClosed
            }
        }
        payments.existing(command)?.let { return OneTimePaymentPreparationResult.Ready(it) }
        if (order.state != OrderState.PENDING_PAYMENT || order.payableKrw <= 0) {
            throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for one-time payment")
        }
        return OneTimePaymentPreparationResult.Ready(payments.prepare(command))
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
