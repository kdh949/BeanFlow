package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import io.github.kdh949.beanflow.payment.api.ExternalPaymentOperations
import io.github.kdh949.beanflow.payment.api.PaymentPreparation
import io.github.kdh949.beanflow.payment.api.PaymentPreparationState
import io.github.kdh949.beanflow.payment.api.PrepareExternalPaymentCommand
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
internal class PaymentConfirmationService(
    private val preparationTransaction: PaymentPreparationTransaction,
    private val resultTransaction: PaymentResultTransaction,
    private val paymentOperations: ExternalPaymentOperations,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val orderReferenceProjection: PaymentOrderReferenceProjection,
    private val correlationIdSource: CorrelationIdSource,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun confirm(
        customerId: UUID,
        orderId: UUID,
        paymentMethodId: UUID,
        idempotencyKey: String,
    ): StoredHttpResponse {
        val now = clock.instant()
        val command =
            PrepareExternalPaymentCommand(
                actorId = customerId,
                orderId = orderId,
                paymentMethodId = paymentMethodId,
                requestedAmountKrw = preparationTransaction.requestedAmount(customerId, orderId),
                idempotencyKey = idempotencyKey,
                payloadHash = CanonicalPaymentPayload.hash(orderId, paymentMethodId),
                correlationId = correlationIdSource.currentOrCreate(),
                now = now,
            )
        return when (val preparation = preparationTransaction.prepare(command)) {
            is OrderPaymentPreparation.Expired -> {
                throw DomainFailure(FailureCode.RESERVATION_EXPIRED, "Order reservation lease has expired")
            }

            is OrderPaymentPreparation.Ready -> {
                handle(preparation.payment, customerId, orderId)
            }
        }
    }

    private fun handle(
        preparation: PaymentPreparation,
        customerId: UUID,
        orderId: UUID,
    ): StoredHttpResponse =
        when (preparation.state) {
            PaymentPreparationState.IN_PROGRESS -> {
                responseFactory.error(
                    FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "An identical payment request is still processing",
                    preparation.current?.correlationId ?: correlationIdSource.currentOrCreate(),
                )
            }

            PaymentPreparationState.CURRENT -> {
                responseFactory.current(
                    requireNotNull(preparation.current),
                    orderReferenceProjection.resolveOwned(customerId, orderId),
                    replay = true,
                )
            }

            PaymentPreparationState.ACQUIRED -> {
                val sample = Timer.start(meterRegistry)
                val result =
                    try {
                        paymentOperations.requestProviderApproval(preparation.paymentId)
                    } catch (failure: ProviderTransportFailure) {
                        logger.warn(
                            "payment_approval paymentId={} outcome=UNKNOWN reason=PROVIDER_CALL_FAILED",
                            preparation.paymentId,
                        )
                        ProviderPaymentResult.Unknown("PROVIDER_CALL_FAILED")
                    } catch (failure: DataAccessException) {
                        throw DomainFailure(
                            FailureCode.DEPENDENCY_UNAVAILABLE,
                            "Payment approval request could not be prepared",
                        )
                    } finally {
                        sample.stop(meterRegistry.timer("beanflow.payment.approval.duration"))
                    }
                val outcome =
                    when (result) {
                        is ProviderPaymentResult.Approved -> "approved"
                        is ProviderPaymentResult.Declined -> "declined"
                        is ProviderPaymentResult.Unknown -> "unknown"
                    }
                meterRegistry.counter("beanflow.payment.approval.attempts", "outcome", outcome).increment()
                try {
                    resultTransaction.apply(customerId, orderId, preparation.paymentId, result, clock.instant())
                } catch (failure: DataAccessException) {
                    throw DomainFailure(
                        FailureCode.DEPENDENCY_UNAVAILABLE,
                        "Payment result could not be committed and will be reconciled",
                    )
                }
            }
        }
}
