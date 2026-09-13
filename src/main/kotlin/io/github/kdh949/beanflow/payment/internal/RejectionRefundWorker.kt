package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderTransportFailure
import io.github.kdh949.beanflow.shared.api.WorkerOwner
import io.github.kdh949.beanflow.shared.api.WorkerTelemetry
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
    private val workerTelemetry: WorkerTelemetry,
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

    fun runOnce(): Int =
        workerTelemetry.observe(WorkerOwner.REJECTION_REFUND) { run ->
            val claimedAt = clock.instant()
            val claims = refundService.claimDue(claimedAt, chunkSize)
            val claimStarted = System.nanoTime()
            run.dataReadSucceeded()
            run.claimed(claims.size)
            claims.forEach { claim ->
                run.claimLag(Duration.between(claim.dueAt, claimedAt))
                meterRegistry
                    .summary("beanflow.payment.refund.lag")
                    .record(Duration.between(claim.dueAt, claimedAt).toMillis().coerceAtLeast(0) / 1000.0)
                try {
                    refundService.recordResult(claim, refundService.callProvider(claim), clock.instant())
                    run.completedAfter(Duration.ofNanos(System.nanoTime() - claimStarted))
                } catch (failure: ProviderTransportFailure) {
                    recordProviderFailure(claim)
                    run.failedAfter(Duration.ofNanos(System.nanoTime() - claimStarted))
                } catch (failure: RuntimeException) {
                    logger.error(
                        "rejection_refund refundId={} paymentId={} mode={} outcome=CLAIM_RETAINED attempt={}",
                        claim.refundId,
                        claim.paymentId,
                        claim.mode,
                        claim.attemptCount,
                        failure,
                    )
                    run.failedAfter(Duration.ofNanos(System.nanoTime() - claimStarted))
                }
            }
            claims.size
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
