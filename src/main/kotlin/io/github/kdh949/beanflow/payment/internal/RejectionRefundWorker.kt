package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class RejectionRefundWorker(
    private val refundService: RejectionRefundService,
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
        val claims = refundService.claimDue(claimedAt, chunkSize)
        claims.forEach { claim ->
            meterRegistry
                .summary("beanflow.payment.refund.lag")
                .record(Duration.between(claim.dueAt, claimedAt).toMillis().coerceAtLeast(0) / 1000.0)
            try {
                refundService.recordResult(claim, refundService.callProvider(claim), clock.instant())
            } catch (failure: ProviderTransportFailure) {
                recordProviderFailure(claim)
            } catch (failure: RuntimeException) {
                logger.error(
                    "rejection_refund refundId={} paymentId={} mode={} outcome=CLAIM_RETAINED attempt={}",
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

    private fun recordProviderFailure(claim: ClaimedRefund) {
        try {
            refundService.recordResult(
                claim,
                GatewayRefundResult.Unknown("PROVIDER_CALL_FAILED"),
                clock.instant(),
            )
        } catch (failure: RuntimeException) {
            logger.error(
                "rejection_refund refundId={} paymentId={} mode={} outcome=CLAIM_RETAINED",
                claim.refundId,
                claim.paymentId,
                claim.mode,
                failure,
            )
        }
    }
}
