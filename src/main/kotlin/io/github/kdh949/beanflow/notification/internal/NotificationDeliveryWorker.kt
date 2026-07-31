package io.github.kdh949.beanflow.notification.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class NotificationDeliveryWorker(
    private val deliveryService: NotificationDeliveryService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.notification.chunk-size:50}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.notification.fixed-delay-ms:5000}",
        initialDelayString = "\${beanflow.notification.initial-delay-ms:15000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val claimedAt = clock.instant()
        val claims = deliveryService.claimDue(claimedAt, chunkSize)
        claims.forEach { claim ->
            meterRegistry
                .summary("beanflow.notification.delivery.lag")
                .record(Duration.between(claim.dueAt, claimedAt).toMillis().coerceAtLeast(0) / 1000.0)
            try {
                deliveryService.recordResult(claim, deliveryService.callProvider(claim), clock.instant())
            } catch (failure: NotificationTransportFailure) {
                recordProviderFailure(claim)
            } catch (failure: RuntimeException) {
                logger.error(
                    "notification_delivery deliveryId={} template={} outcome=CLAIM_RETAINED attempt={}",
                    claim.deliveryId,
                    claim.template,
                    claim.attemptCount,
                    failure,
                )
            }
        }
        return claims.size
    }

    private fun recordProviderFailure(claim: ClaimedNotificationDelivery) {
        try {
            deliveryService.recordResult(
                claim,
                NotificationProviderResult.Unknown("PROVIDER_CALL_FAILED"),
                clock.instant(),
            )
        } catch (failure: RuntimeException) {
            logger.error(
                "notification_delivery deliveryId={} template={} outcome=CLAIM_RETAINED",
                claim.deliveryId,
                claim.template,
                failure,
            )
        }
    }
}
