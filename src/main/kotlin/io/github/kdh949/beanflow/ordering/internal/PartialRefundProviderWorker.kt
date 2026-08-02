package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentOperations
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class PartialRefundProviderWorker(
    private val paymentOperations: PartialRefundPaymentOperations,
    private val execution: PartialRefundProviderExecutionService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment.refund.chunk-size:50}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.payment.refund.fixed-delay-ms:5000}",
        initialDelayString = "\${beanflow.payment.refund.initial-delay-ms:15000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val claimedAt = clock.instant()
        val claims = paymentOperations.claimDueProviders(claimedAt, chunkSize)
        claims.forEach { claim ->
            meterRegistry
                .summary("beanflow.payment.refund.lag")
                .record(Duration.between(claim.dueAt, claimedAt).toMillis().coerceAtLeast(0) / 1000.0)
            try {
                execution.process(claim)
            } catch (failure: RuntimeException) {
                logger.error(
                    "partial_refund refundId={} paymentId={} mode={} outcome=CLAIM_RETAINED attempt={}",
                    claim.refundId,
                    claim.paymentId,
                    claim.mode,
                    claim.attemptCount,
                    failure,
                )
            }
        }
        return claims.size
    }
}
