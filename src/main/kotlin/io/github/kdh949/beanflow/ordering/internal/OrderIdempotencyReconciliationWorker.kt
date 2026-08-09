package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class OrderIdempotencyReconciliationOutcome {
    MANUAL_REVIEW_NO_ORDER,
    MANUAL_REVIEW_ORDER_FOUND,
    NOT_PROCESSING,
}

internal data class OrderIdempotencyReconciliationResult(
    val operation: String,
    val outcome: OrderIdempotencyReconciliationOutcome,
)

@Service
internal class OrderIdempotencyReconciliationService(
    private val records: IdempotencyRecordJpaRepository,
    private val orders: OrderJpaRepository,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reconcile(recordId: UUID): OrderIdempotencyReconciliationResult {
        val record = records.findLockedById(recordId) ?: return unknownNotProcessing()
        if (record.status != IdempotencyStatus.PROCESSING) {
            return OrderIdempotencyReconciliationResult(record.operation, OrderIdempotencyReconciliationOutcome.NOT_PROCESSING)
        }
        val orderExists = orders.existsById(record.intendedOrderId)
        record.status = IdempotencyStatus.MANUAL_REVIEW
        record.manualReviewReason =
            if (orderExists) {
                IdempotencyManualReviewReason.ORDER_FOUND
            } else {
                IdempotencyManualReviewReason.ORDER_NOT_FOUND
            }
        record.manualReviewStartedAt = clock.instant()
        record.intendedOrderExists = orderExists
        return OrderIdempotencyReconciliationResult(
            operation = record.operation,
            outcome =
                if (orderExists) {
                    OrderIdempotencyReconciliationOutcome.MANUAL_REVIEW_ORDER_FOUND
                } else {
                    OrderIdempotencyReconciliationOutcome.MANUAL_REVIEW_NO_ORDER
                },
        )
    }

    private fun unknownNotProcessing() =
        OrderIdempotencyReconciliationResult("UNKNOWN", OrderIdempotencyReconciliationOutcome.NOT_PROCESSING)
}

@Component
internal class OrderIdempotencyReconciliationWorker(
    private val records: IdempotencyRecordJpaRepository,
    private val reconciliation: OrderIdempotencyReconciliationService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${beanflow.idempotency.stuck-threshold:PT5M}")
    private val stuckThreshold: Duration,
    @Value("\${beanflow.idempotency.reconciliation-chunk-size:100}")
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.idempotency.reconciliation-delay-ms:30000}",
        initialDelayString = "\${beanflow.idempotency.reconciliation-initial-delay-ms:30000}",
    )
    fun runScheduled() {
        runOnce()
    }

    fun runOnce(): Int {
        require(chunkSize > 0)
        val cutoff = clock.instant().minus(stuckThreshold)
        val ids = records.findStuckProcessingIds(cutoff, PageRequest.of(0, chunkSize))
        ids.forEach { id ->
            try {
                val result = reconciliation.reconcile(id)
                val operation = operationTag(result.operation)
                meterRegistry
                    .counter(
                        "beanflow.order.idempotency.reconciliation",
                        "operation",
                        operation,
                        "outcome",
                        result.outcome.name,
                    ).increment()
                logger.warn(
                    "order_idempotency_reconciliation operation={} outcome={}",
                    operation,
                    result.outcome.name,
                )
            } catch (failure: RuntimeException) {
                meterRegistry
                    .counter(
                        "beanflow.order.idempotency.reconciliation",
                        "operation",
                        "UNKNOWN",
                        "outcome",
                        "FAILED",
                    ).increment()
                logger.error(
                    "order_idempotency_reconciliation operation=UNKNOWN outcome=FAILED failureType={}",
                    failure.javaClass.simpleName,
                )
            }
        }
        return ids.size
    }

    private fun operationTag(operation: String): String =
        when (operation) {
            OrderCreationOperation.DIRECT.value -> OrderCreationOperation.DIRECT.value
            OrderCreationOperation.REORDER.value -> OrderCreationOperation.REORDER.value
            else -> "UNKNOWN"
        }
}
