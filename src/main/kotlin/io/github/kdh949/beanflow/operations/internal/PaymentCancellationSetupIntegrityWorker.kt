package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.DetectPaymentCancellationSetupIssueCommand
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class PaymentCancellationSetupIntegrityWorker(
    private val queries: PaymentCancellationSetupIntegrityQueryService,
    private val integrity: PaymentCancellationSetupIntegrityOperations,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment-cancellation-setup.batch-size:100}")
    private val batchSize: Int,
) {
    init {
        require(batchSize in 1..100) { "Payment cancellation setup batch size must be between 1 and 100" }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.payment-cancellation-setup.fixed-delay-ms:60000}",
        initialDelayString = "\${beanflow.payment-cancellation-setup.initial-delay-ms:60000}",
    )
    fun runScheduled() {
        val now = clock.instant()
        val sample = Timer.start(meterRegistry)
        try {
            val keys = queries.findScanKeys(batchSize)
            var candidates = 0
            keys.forEach { key ->
                queries.assess(key.orderId, key.cancellationOrderVersion)?.let { assessment ->
                    candidates++
                    integrity.detect(
                        DetectPaymentCancellationSetupIssueCommand(
                            orderId = assessment.orderId,
                            cancellationOrderVersion = assessment.cancellationOrderVersion,
                            missingArtifacts = assessment.missingArtifacts,
                            invariantViolations = assessment.invariantViolations,
                            errorCode = assessment.errorCode,
                            correlationId = assessment.correlationId,
                            now = now,
                        ),
                    )
                }
            }
            meterRegistry.counter("beanflow.operations.payment_setup.scan.count", "outcome", "succeeded").increment()
            meterRegistry.summary("beanflow.operations.payment_setup.scan.candidates").record(candidates.toDouble())
            queries.oldestOpenCaseCreatedAt()?.let { createdAt ->
                meterRegistry
                    .summary("beanflow.operations.payment_setup.oldest_age.seconds")
                    .record(
                        Duration
                            .between(createdAt, now)
                            .seconds
                            .coerceAtLeast(0)
                            .toDouble(),
                    )
            }
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.operations.payment_setup.scan.count", "outcome", "failed").increment()
            throw failure
        } finally {
            sample.stop(meterRegistry.timer("beanflow.operations.payment_setup.scan.duration"))
        }
    }
}
