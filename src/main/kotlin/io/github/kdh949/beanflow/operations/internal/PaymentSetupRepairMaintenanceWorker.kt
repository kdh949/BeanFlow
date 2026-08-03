package io.github.kdh949.beanflow.operations.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
internal class PaymentSetupRepairMaintenanceWorker(
    private val service: PaymentSetupRepairService,
    private val refundReconciliations: CustomerCancellationRefundReconciliationService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.payment-setup-repair-maintenance.chunk-size:100}")
    private val chunkSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.payment-setup-repair-maintenance.fixed-delay-ms:60000}",
        initialDelayString = "\${beanflow.payment-setup-repair-maintenance.initial-delay-ms:60000}",
    )
    fun runScheduled() {
        val now = clock.instant()
        try {
            val expired = service.expireDue(now, chunkSize)
            val deleted = service.purgeIdempotencyDue(now, chunkSize)
            val reconciliationDeleted = refundReconciliations.purgeDue(now, chunkSize)
            meterRegistry.counter("beanflow.operations.payment_setup.proposal.expired").increment(expired.toDouble())
            meterRegistry.counter("beanflow.operations.payment_setup.idempotency.retention.deleted").increment(deleted.toDouble())
            if (expired > 0 || deleted > 0 || reconciliationDeleted > 0) {
                logger.info(
                    "payment_setup_repair_maintenance outcome=COMPLETED expiredCount={} " +
                        "idempotencyDeletedCount={} refundReconciliationDeletedCount={}",
                    expired,
                    deleted,
                    reconciliationDeleted,
                )
            }
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.operations.payment_setup.maintenance.failure").increment()
            logger.error("payment_setup_repair_maintenance outcome=FAILED", failure)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PaymentSetupRepairMaintenanceWorker::class.java)
    }
}
