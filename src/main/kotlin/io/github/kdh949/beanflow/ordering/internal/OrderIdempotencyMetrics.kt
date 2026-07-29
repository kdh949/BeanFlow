package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
internal class OrderIdempotencyMetrics(
	private val repository: IdempotencyRecordJpaRepository,
	private val clock: Clock,
	meterRegistry: MeterRegistry,
	@Value("\${beanflow.idempotency.stuck-threshold:PT5M}")
	private val stuckThreshold: Duration,
) {

	private val stuckCount = AtomicLong(0)

	init {
		Gauge.builder("beanflow.order.idempotency.processing.stuck", stuckCount) { value ->
			value.get().toDouble()
		}
			.register(meterRegistry)
	}

	@Scheduled(
		fixedDelayString = "\${beanflow.idempotency.metrics-delay-ms:30000}",
		initialDelayString = "\${beanflow.idempotency.metrics-initial-delay-ms:30000}",
	)
	fun refresh() {
		stuckCount.set(
			repository.countByStatusAndStartedAtBefore(
				IdempotencyStatus.PROCESSING,
				clock.instant().minus(stuckThreshold),
			),
		)
	}
}
