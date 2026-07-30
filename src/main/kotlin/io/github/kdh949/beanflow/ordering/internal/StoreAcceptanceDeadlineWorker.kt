package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
internal class StoreAcceptanceDeadlineWorker(
    private val orderRepository: OrderJpaRepository,
    private val service: StoreAcceptanceDeadlineService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.store-acceptance.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.store-acceptance.fixed-delay-ms:1000}",
        initialDelayString = "\${beanflow.store-acceptance.initial-delay-ms:60000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val now = clock.instant()
        val page = PageRequest.of(0, chunkSize)
        orderRepository.findAcceptanceWarningDueIds(now, page).forEach { orderId ->
            try {
                val outcome = service.requestWarning(orderId, now)
                meterRegistry
                    .counter(
                        "beanflow.order.acceptance.warning.count",
                        "outcome",
                        outcome.name.lowercase(),
                    ).increment()
            } catch (failure: RuntimeException) {
                logger.error("store_acceptance_warning orderId={} outcome=FAILED", orderId, failure)
                meterRegistry
                    .counter(
                        "beanflow.order.acceptance.warning.count",
                        "outcome",
                        "failed",
                    ).increment()
            }
        }
        var rejected = 0
        orderRepository.findAcceptanceTimeoutDueIds(now, page).forEach { orderId ->
            try {
                if (service.rejectTimedOut(orderId, now) == StoreAcceptanceDeadlineOutcome.APPLIED) {
                    rejected++
                }
            } catch (failure: RuntimeException) {
                logger.error("store_acceptance_timeout orderId={} outcome=FAILED", orderId, failure)
            }
        }
        if (rejected > 0) {
            meterRegistry
                .counter(
                    "beanflow.order.acceptance.timeout.count",
                    "outcome",
                    "rejected",
                ).increment(rejected.toDouble())
        }
        return rejected
    }
}
