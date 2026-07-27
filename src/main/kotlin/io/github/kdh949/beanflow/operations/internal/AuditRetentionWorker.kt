package io.github.kdh949.beanflow.operations.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
internal class AuditRetentionWorker(
	private val auditRecordService: AuditRecordService,
	private val clock: Clock,
	private val meterRegistry: MeterRegistry,
	@Value("\${beanflow.audit-retention.chunk-size:100}")
	private val chunkSize: Int,
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${beanflow.audit-retention.fixed-delay-ms:3600000}",
		initialDelayString = "\${beanflow.audit-retention.initial-delay-ms:300000}",
	)
	fun runScheduled() {
		runOnce()
	}

	fun runOnce(): Int {
		val now = clock.instant()
		return try {
			val result = auditRecordService.purgeDue(now, chunkSize)
			meterRegistry.counter("beanflow.audit.retention.deleted").increment(result.deletedCount.toDouble())
			result.oldestDueAt?.let { oldestDueAt ->
				meterRegistry.summary("beanflow.audit.retention.oldest_due_age.seconds")
					.record(Duration.between(oldestDueAt, now).toMillis().coerceAtLeast(0) / 1000.0)
			}
			if (result.deletedCount > 0) {
				logger.info(
					"audit_retention outcome=DELETED deletedCount={} oldestDueAt={}",
					result.deletedCount,
					result.oldestDueAt,
				)
			}
			result.deletedCount
		} catch (failure: RuntimeException) {
			meterRegistry.counter("beanflow.audit.retention.failure").increment()
			logger.error("audit_retention outcome=FAILED", failure)
			throw failure
		}
	}
}
