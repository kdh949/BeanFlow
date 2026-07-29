package io.github.kdh949.beanflow.payment.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
internal class PaymentMetrics(
	private val paymentRepository: PaymentJpaRepository,
	private val clock: Clock,
	meterRegistry: MeterRegistry,
) {
	private val oldestUnknownAgeSeconds = meterRegistry.gauge(
		"beanflow.payment.unknown.age",
		AtomicLong(0),
	)

	@Scheduled(
		fixedDelayString = "\${beanflow.payment.metrics.unknown-age-delay-ms:30000}",
		initialDelayString = "\${beanflow.payment.metrics.unknown-age-initial-delay-ms:30000}",
	)
	@Transactional(readOnly = true)
	fun refreshUnknownAge() {
		val now = clock.instant()
		val age = paymentRepository.findOldestUnknownUpdatedAt()?.let {
			Duration.between(it, now).seconds.coerceAtLeast(0)
		} ?: 0
		oldestUnknownAgeSeconds.set(age)
	}
}
