package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReservationExpiryOutcome
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.shared.api.WorkerOwner
import io.github.kdh949.beanflow.shared.api.WorkerTelemetry
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

@Component
@EnableScheduling
internal class ReservationExpiryWorker(
    private val orderRepository: OrderJpaRepository,
    private val expiryUseCase: ReservationExpiryUseCase,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    private val workerTelemetry: WorkerTelemetry,
    @Value("\${beanflow.reservation-expiry.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dueCount =
        meterRegistry.gauge(
            "beanflow.reservation.due.count",
            AtomicInteger(0),
        )

    @Scheduled(
        fixedDelayString = "\${beanflow.reservation-expiry.fixed-delay-ms:30000}",
        initialDelayString = "\${beanflow.reservation-expiry.initial-delay-ms:60000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int =
        workerTelemetry.observe(WorkerOwner.RESERVATION_EXPIRY) { run ->
            val started = System.nanoTime()
            val now = clock.instant()
            val dueIds = orderRepository.findDueIds(now, PageRequest.of(0, chunkSize))
            run.dataReadSucceeded()
            dueCount.set(dueIds.size)
            var expired = 0
            dueIds.forEach { orderId ->
                try {
                    val result = expiryUseCase.expireIfDue(orderId, now)
                    if (result.outcome == ReservationExpiryOutcome.EXPIRED) {
                        expired++
                    }
                    run.completed()
                } catch (failure: RuntimeException) {
                    run.failed()
                    logger.error("reservation_expiry_worker orderId={} outcome=FAILED", orderId, failure)
                }
            }
            meterRegistry
                .timer("beanflow.reservation.expiry.chunk.duration")
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS)
            expired
        }
}
