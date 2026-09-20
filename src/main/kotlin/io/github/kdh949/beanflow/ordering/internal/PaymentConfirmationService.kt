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
import io.github.kdh949.beanflow.shared.api.PerformanceOperation
import io.github.kdh949.beanflow.shared.api.PerformancePhaseTelemetry
import io.github.kdh949.beanflow.shared.api.PerformanceStage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import java.time.Clock
import java.util.UUID

@Service
internal class PaymentConfirmationService(
    private val preparationTransaction: PaymentPreparationTransaction,
    private val resultTransaction: PaymentResultTransaction,
    private val commitmentRecovery: PaymentCommitmentRecoveryTransaction,
    private val paymentOperations: ExternalPaymentOperations,
    private val responseFactory: PaymentConfirmationResponseFactory,
    private val orderReferenceProjection: PaymentOrderReferenceProjection,
    private val correlationIdSource: CorrelationIdSource,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val phaseTelemetry: PerformancePhaseTelemetry,
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
        val preparation =
            phaseTelemetry.observe(PerformanceOperation.PAYMENT_CONFIRM, PerformanceStage.PAYMENT_PREPARE) {
                preparationTransaction.prepare(command)
            }
        return when (preparation) {
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
                        phaseTelemetry.observe(PerformanceOperation.PAYMENT_CONFIRM, PerformanceStage.PROVIDER_CALL) {
                            paymentOperations.requestProviderApproval(preparation.paymentId)
                        }
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
                val appliedAt = clock.instant()
                try {
                    phaseTelemetry.observe(PerformanceOperation.PAYMENT_CONFIRM, PerformanceStage.RESULT_APPLY) {
                        resultTransaction.apply(customerId, orderId, preparation.paymentId, result, appliedAt)
                    }
                } catch (failure: ImmediatePaymentCommitmentFailure) {
                    recoverApproved(customerId, orderId, preparation.paymentId, result, failure.failureCode.name, appliedAt)
                } catch (failure: DataAccessException) {
                    recoverApproved(customerId, orderId, preparation.paymentId, result, FailureCode.DEPENDENCY_UNAVAILABLE.name, appliedAt)
                } catch (failure: TransactionException) {
                    recoverApproved(customerId, orderId, preparation.paymentId, result, FailureCode.DEPENDENCY_UNAVAILABLE.name, appliedAt)
                }
            }
        }

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
                    "Payment result could not be committed and will be reconciled",
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
}
