package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal data class OrderingIdempotencyPurgeResult(
    val deletedCount: Int,
    val oldestDueAt: Instant?,
    val remainingBacklog: Long,
)

@Service
internal class StoreCommandIdempotencyRetentionService(
    private val records: StoreCommandIdempotencyJpaRepository,
) {
    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): OrderingIdempotencyPurgeResult {
        require(chunkSize > 0)
        val ids = records.findDueIds(now, PageRequest.of(0, chunkSize))
        val oldestDueAt = records.findAllById(ids).minOfOrNull(StoreCommandIdempotencyEntity::retentionExpiresAt)
        if (ids.isNotEmpty()) records.deleteAllByIdInBatch(ids)
        return OrderingIdempotencyPurgeResult(ids.size, oldestDueAt, records.countByRetentionExpiresAtLessThanEqual(now))
    }
}

@Service
internal class CancellationCommandIdempotencyRetentionService(
    private val records: CancellationCommandIdempotencyJpaRepository,
) {
    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): OrderingIdempotencyPurgeResult {
        require(chunkSize > 0)
        val ids = records.findDueIds(now, PageRequest.of(0, chunkSize))
        val oldestDueAt = records.findAllById(ids).minOfOrNull(CancellationCommandIdempotencyEntity::retentionExpiresAt)
        if (ids.isNotEmpty()) records.deleteAllByIdInBatch(ids)
        return OrderingIdempotencyPurgeResult(ids.size, oldestDueAt, records.countByRetentionExpiresAtLessThanEqual(now))
    }
}

@Service
internal class OrderCreationIdempotencyRetentionService(
    private val records: IdempotencyRecordJpaRepository,
) {
    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): OrderingIdempotencyPurgeResult {
        require(chunkSize > 0)
        val ids = records.findDueIds(now, PageRequest.of(0, chunkSize))
        val oldestDueAt = records.findAllById(ids).mapNotNull(IdempotencyRecordEntity::retentionExpiresAt).minOrNull()
        if (ids.isNotEmpty()) records.deleteAllByIdInBatch(ids)
        return OrderingIdempotencyPurgeResult(ids.size, oldestDueAt, records.countByRetentionExpiresAtLessThanEqual(now))
    }
}

@Component
internal class OrderingIdempotencyRetentionWorker(
    private val orderCreationRecords: OrderCreationIdempotencyRetentionService,
    private val storeRecords: StoreCommandIdempotencyRetentionService,
    private val cancellationRecords: CancellationCommandIdempotencyRetentionService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.ordering-idempotency-retention.chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val orderCreationBacklog = AtomicLong()
    private val storeBacklog = AtomicLong()
    private val cancellationBacklog = AtomicLong()

    init {
        meterRegistry.gauge(
            "beanflow.ordering.idempotency.retention.backlog",
            listOf(io.micrometer.core.instrument.Tag.of("table", TABLE_ORDER_CREATION)),
            orderCreationBacklog,
        )
        meterRegistry.gauge(
            "beanflow.ordering.idempotency.retention.backlog",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("table", TABLE_STORE),
            ),
            storeBacklog,
        )
        meterRegistry.gauge(
            "beanflow.ordering.idempotency.retention.backlog",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("table", TABLE_CANCELLATION),
            ),
            cancellationBacklog,
        )
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.ordering-idempotency-retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.ordering-idempotency-retention.initial-delay-ms:300000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        val now = clock.instant()
        val orderCreationDeleted = purge(TABLE_ORDER_CREATION, now) { orderCreationRecords.purgeDue(now, chunkSize) }
        val storeDeleted = purge(TABLE_STORE, now) { storeRecords.purgeDue(now, chunkSize) }
        val cancellationDeleted = purge(TABLE_CANCELLATION, now) { cancellationRecords.purgeDue(now, chunkSize) }
        return orderCreationDeleted + storeDeleted + cancellationDeleted
    }

    private fun purge(
        table: String,
        now: Instant,
        operation: () -> OrderingIdempotencyPurgeResult,
    ): Int =
        try {
            val result = operation()
            meterRegistry
                .counter("beanflow.ordering.idempotency.retention.deleted", "table", table)
                .increment(result.deletedCount.toDouble())
            backlog(table).set(result.remainingBacklog)
            result.oldestDueAt?.let {
                meterRegistry
                    .summary("beanflow.ordering.idempotency.retention.oldest_due_age.seconds", "table", table)
                    .record(Duration.between(it, now).toMillis().coerceAtLeast(0) / 1000.0)
            }
            if (result.deletedCount > 0) {
                logger.info(
                    "ordering_idempotency_retention table={} outcome=DELETED deletedCount={} oldestDueAgeSeconds={}",
                    table,
                    result.deletedCount,
                    result.oldestDueAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) },
                )
            }
            result.deletedCount
        } catch (failure: RuntimeException) {
            meterRegistry.counter("beanflow.ordering.idempotency.retention.failure", "table", table).increment()
            logger.error(
                "ordering_idempotency_retention table={} outcome=FAILED failureType={}",
                table,
                failure.javaClass.simpleName,
            )
            0
        }

    private fun backlog(table: String): AtomicLong =
        when (table) {
            TABLE_ORDER_CREATION -> orderCreationBacklog
            TABLE_STORE -> storeBacklog
            else -> cancellationBacklog
        }

    private companion object {
        const val TABLE_ORDER_CREATION = "order_creation"
        const val TABLE_STORE = "store_command"
        const val TABLE_CANCELLATION = "cancellation_command"
    }
}
