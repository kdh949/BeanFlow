package io.github.kdh949.beanflow.notification.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
internal class NotificationInboxCleanupWorker(
    private val service: NotificationInboxService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.notification.inbox-cleanup.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.notification.inbox-cleanup.initial-delay-ms:3600000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int =
        try {
            service.purgeExpired(clock.instant()).also { deleted ->
                meterRegistry.counter("beanflow.notification.inbox_cleanup.count", "outcome", "succeeded").increment()
                meterRegistry.summary("beanflow.notification.inbox_cleanup.deleted").record(deleted.toDouble())
            }
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.notification.inbox_cleanup.count", "outcome", "failed").increment()
            throw failure
        }
}
