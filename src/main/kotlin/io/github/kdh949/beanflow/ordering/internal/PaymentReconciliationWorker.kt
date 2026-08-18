package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.payment.api.ClaimedPaymentReconciliation
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationOperations
import io.github.kdh949.beanflow.payment.api.PaymentReconciliationWorkKind
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.api.ProviderRecoveryOutcome
import io.github.kdh949.beanflow.payment.api.ProviderRecoveryResult
import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class PaymentReconciliationWorker(
    private val reconciliationOperations: PaymentReconciliationOperations,
    private val resultTransaction: PaymentResultTransaction,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.reconciliation.chunk-size:50}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.payment.reconciliation.fixed-delay-ms:5000}",
        initialDelayString = "\${beanflow.payment.reconciliation.initial-delay-ms:15000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val now = clock.instant()
        val claims = reconciliationOperations.claimDue(now, chunkSize)
        claims.forEach { work ->
            meterRegistry
                .summary("beanflow.payment.reconciliation.lag")
                .record(Duration.between(work.dueAt, now).toMillis().coerceAtLeast(0) / 1000.0)
            try {
                process(work)
            } catch (failure: ProviderTransportFailure) {
                logger.warn(
                    "payment_reconciliation paymentId={} kind={} outcome=PROVIDER_UNKNOWN attempt={}",
                    work.paymentId,
                    work.kind,
                    work.attemptCount + 1,
                )
                recordProviderFailure(work)
            } catch (failure: RuntimeException) {
                logger.error(
                    "payment_reconciliation paymentId={} kind={} outcome=CLAIM_RETAINED attempt={}",
                    work.paymentId,
                    work.kind,
                    work.attemptCount + 1,
                    failure,
                )
            }
        }
        return claims.size
    }

    private fun process(work: ClaimedPaymentReconciliation) {
        val now = clock.instant()
        when (work.kind) {
            PaymentReconciliationWorkKind.APPROVAL_LOOKUP -> {
                val result = reconciliationOperations.requestProviderLookup(work.paymentId)
                when (result) {
                    is ProviderPaymentResult.Unknown -> {
                        resultTransaction.reconcileUnknown(work, result, now)
                    }

                    is ProviderPaymentResult.Approved -> {
                        if (result.amountKrw == work.requestedAmountKrw && result.currency == work.currency) {
                            resultTransaction.apply(
                                work.customerId,
                                work.orderId,
                                work.paymentId,
                                result,
                                now,
                            )
                        } else {
                            resultTransaction.reconcileMismatch(work, result, now)
                        }
                    }

                    else -> {
                        resultTransaction.apply(
                            work.customerId,
                            work.orderId,
                            work.paymentId,
                            result,
                            now,
                        )
                    }
                }
            }

            PaymentReconciliationWorkKind.LATE_VOID,
            PaymentReconciliationWorkKind.LATE_REFUND,
            -> {
                resultTransaction.reconcileRecovery(
                    work,
                    reconciliationOperations.requestProviderRecovery(work),
                    now,
                )
            }
        }
    }

    private fun recordProviderFailure(work: ClaimedPaymentReconciliation) {
        try {
            val now = clock.instant()
            when (work.kind) {
                PaymentReconciliationWorkKind.APPROVAL_LOOKUP -> {
                    resultTransaction.reconcileUnknown(
                        work,
                        ProviderPaymentResult.Unknown("PROVIDER_CALL_FAILED"),
                        now,
                    )
                }

                PaymentReconciliationWorkKind.LATE_VOID,
                PaymentReconciliationWorkKind.LATE_REFUND,
                -> {
                    resultTransaction.reconcileRecovery(
                        work,
                        ProviderRecoveryResult(ProviderRecoveryOutcome.UNKNOWN, "PROVIDER_CALL_FAILED"),
                        now,
                    )
                }
            }
        } catch (failure: RuntimeException) {
            logger.error(
                "payment_reconciliation paymentId={} kind={} outcome=CLAIM_RETAINED",
                work.paymentId,
                work.kind,
                failure,
            )
        }
    }
}
