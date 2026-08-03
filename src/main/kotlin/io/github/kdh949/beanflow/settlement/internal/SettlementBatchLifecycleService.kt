package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class SettlementBatchLifecycleResult(
    val settlementBatchId: UUID,
    val state: SettlementBatchState,
    val itemCount: Int,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val adjustmentKrw: Long,
    val netSettlementKrw: Long,
    val carryForwardOutKrw: Long,
    val calculatedAt: Instant,
    val confirmedAt: Instant?,
)

@Service
internal class SettlementBatchLifecycleService(
    private val batches: SettlementBatchJpaRepository,
    private val repository: SettlementBatchLifecycleRepository,
    private val audits: AuditRecordOperations,
    private val financialEvents: FinancialEventPublicationOperations,
    private val identifierSource: IdentifierSource,
    private val metrics: SettlementBatchMetrics,
) {
    @Transactional
    fun calculate(
        settlementBatchId: UUID,
        calculatedAt: Instant,
    ): SettlementBatchLifecycleResult =
        try {
            val batch = batches.findLockedById(settlementBatchId) ?: notFound()
            if (batch.state != SettlementBatchState.OPEN) return batch.toLifecycleResult()
            if (!calculatedAt.atZone(SEOUL).toLocalDate().isAfter(batch.settlementDate)) {
                conflict("SettlementBatch can be calculated only after its Seoul settlement date")
            }
            if (repository.hasEarlierUnconfirmedBatch(batch.storeId, batch.settlementDate)) {
                conflict("Earlier SettlementBatch must be confirmed before this date")
            }
            val previous = repository.findPreviousConfirmedBatch(batch.storeId, batch.settlementDate)
            val itemSummary = sumItems(batch.id)
            val adjustmentSummary = sumAdjustments(batch.storeId, previous, calculatedAt)
            val summary =
                SettlementBatchCalculation(
                    itemCount = itemSummary.itemCount,
                    grossPaidKrw = itemSummary.grossPaidKrw,
                    feeKrw = itemSummary.feeKrw,
                    benefitCostKrw = itemSummary.benefitCostKrw,
                    itemNetSettlementKrw = itemSummary.netSettlementKrw,
                    adjustmentKrw = adjustmentSummary.amountKrw,
                    carryForwardInKrw = previous?.carryForwardOutKrw ?: 0,
                    carryForwardSourceBatchId = previous?.takeIf { it.carryForwardOutKrw < 0 }?.settlementBatchId,
                    adjustmentCursorEffectiveAt =
                        adjustmentSummary.lastEffectiveAt ?: previous?.adjustmentCursorEffectiveAt,
                    adjustmentCursorId = adjustmentSummary.lastAdjustmentId ?: previous?.adjustmentCursorId,
                )
            batch.calculate(summary, calculatedAt)
            batches.saveAndFlush(batch)
            metrics.recordBatch(SettlementBatchState.CALCULATED, "SUCCEEDED", itemSummary.chunkCount)
            batch.toLifecycleResult()
        } catch (failure: DomainFailure) {
            metrics.recordBatch(SettlementBatchState.OPEN, failure.code.name)
            throw failure
        } catch (failure: ArithmeticException) {
            metrics.recordBatch(SettlementBatchState.OPEN, "AMOUNT_OVERFLOW")
            unavailable("SettlementBatch summary overflowed", failure)
        } catch (failure: DataAccessException) {
            metrics.recordBatch(SettlementBatchState.OPEN, "DEPENDENCY_UNAVAILABLE")
            dependency("SettlementBatch calculation persistence is unavailable", failure)
        }

    @Transactional
    fun confirm(
        settlementBatchId: UUID,
        confirmedAt: Instant,
        correlationId: String,
    ): SettlementBatchLifecycleResult =
        try {
            require(correlationId.isNotBlank()) { "SettlementBatch confirmation correlation is required" }
            val batch = batches.findLockedById(settlementBatchId) ?: notFound()
            if (batch.state == SettlementBatchState.CONFIRMED) return batch.toLifecycleResult()
            if (batch.state != SettlementBatchState.CALCULATED) {
                conflict("SettlementBatch must be calculated before confirmation")
            }
            batch.confirm(confirmedAt)
            batches.saveAndFlush(batch)
            val netSettlementKrw = requireNotNull(batch.netSettlementKrw())
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = SYSTEM_ACTOR,
                        actorType = AuditActorType.SYSTEM,
                        action = "SETTLEMENT_BATCH_CONFIRMED",
                        targetType = "SETTLEMENT_BATCH",
                        targetId = batch.id,
                        occurredAt = confirmedAt,
                        reason = "DAILY_SETTLEMENT_CONFIRMED",
                        beforeSummary = mapOf("state" to SettlementBatchState.CALCULATED.name),
                        afterSummary =
                            mapOf(
                                "state" to SettlementBatchState.CONFIRMED.name,
                                "settlementDate" to batch.settlementDate.toString(),
                                "itemCount" to requireNotNull(batch.itemCount).toString(),
                                "netSettlementKrw" to netSettlementKrw.toString(),
                            ),
                        correlationId = correlationId,
                        sourceReference = "settlement-batch:${batch.id}:confirmed",
                    ),
                ),
            )
            financialEvents.publish(batch.toConfirmedEvent(confirmedAt, correlationId, identifierSource.next()))
            metrics.recordBatch(SettlementBatchState.CONFIRMED, "SUCCEEDED")
            batch.toLifecycleResult()
        } catch (failure: DomainFailure) {
            metrics.recordBatch(SettlementBatchState.CALCULATED, failure.code.name)
            throw failure
        } catch (failure: DataAccessException) {
            metrics.recordBatch(SettlementBatchState.CALCULATED, "DEPENDENCY_UNAVAILABLE")
            dependency("SettlementBatch confirmation persistence is unavailable", failure)
        }

    private fun sumItems(batchId: UUID): ItemSummary {
        var afterCompletedAt: Instant? = null
        var afterItemId: UUID? = null
        var count = 0
        var gross = 0L
        var fee = 0L
        var benefit = 0L
        var net = 0L
        var chunks = 0
        while (true) {
            val chunk = repository.findItemChunk(batchId, afterCompletedAt, afterItemId, CHUNK_SIZE)
            if (chunk.isEmpty()) break
            chunks += 1
            chunk.forEach { item ->
                count = Math.addExact(count, 1)
                gross = Math.addExact(gross, item.grossPaidKrw)
                fee = Math.addExact(fee, item.feeKrw)
                benefit = Math.addExact(benefit, item.benefitCostKrw)
                net = Math.addExact(net, item.netSettlementKrw)
            }
            val last = chunk.last()
            afterCompletedAt = last.completedAt
            afterItemId = last.settlementItemId
            if (chunk.size < CHUNK_SIZE) break
        }
        if (net != Math.subtractExact(Math.subtractExact(gross, fee), benefit)) {
            unavailable("SettlementBatch item projection does not tie out")
        }
        return ItemSummary(count, gross, fee, benefit, net, chunks)
    }

    private fun sumAdjustments(
        storeId: UUID,
        previous: PreviousSettlementBatchProjection?,
        calculatedAt: Instant,
    ): AdjustmentSummary {
        var afterCreatedAt: Instant? = null
        var afterAdjustmentId: UUID? = null
        var total = 0L
        var lastEffectiveAt: Instant? = null
        var lastId: UUID? = null
        while (true) {
            val chunk =
                repository.findAdjustmentChunk(
                    storeId = storeId,
                    createdAfter = previous?.calculatedAt,
                    createdAtOrBefore = calculatedAt,
                    afterCreatedAt = afterCreatedAt,
                    afterAdjustmentId = afterAdjustmentId,
                    limit = CHUNK_SIZE,
                )
            if (chunk.isEmpty()) break
            chunk.forEach { adjustment -> total = Math.addExact(total, adjustment.amountKrw) }
            val last = chunk.last()
            afterCreatedAt = last.createdAt
            afterAdjustmentId = last.settlementAdjustmentId
            lastEffectiveAt = last.effectiveAt
            lastId = last.settlementAdjustmentId
            if (chunk.size < CHUNK_SIZE) break
        }
        return AdjustmentSummary(total, lastEffectiveAt, lastId)
    }

    private fun SettlementBatchEntity.toConfirmedEvent(
        occurredAt: Instant,
        correlationId: String,
        eventId: UUID,
    ): SettlementBatchConfirmedV1 =
        SettlementBatchConfirmedV1(
            envelope =
                EventEnvelope(
                    eventId = eventId,
                    eventType = "SettlementBatchConfirmedV1",
                    aggregateId = id,
                    aggregateVersion = version,
                    occurredAt = occurredAt,
                    payloadVersion = 1,
                    correlationId = correlationId,
                    causationId = "settlement-batch:$id:confirmed",
                ),
            settlementBatchId = id,
            settlementDate = settlementDate,
            state = SettlementBatchState.CONFIRMED.name,
            netSettlementKrw = requireNotNull(netSettlementKrw()),
            currency = "KRW",
        )

    private fun SettlementBatchEntity.toLifecycleResult(): SettlementBatchLifecycleResult =
        SettlementBatchLifecycleResult(
            settlementBatchId = id,
            state = state,
            itemCount = requireNotNull(itemCount),
            grossPaidKrw = requireNotNull(grossPaidKrw),
            feeKrw = requireNotNull(feeKrw),
            benefitCostKrw = requireNotNull(benefitCostKrw),
            adjustmentKrw = requireNotNull(adjustmentKrw),
            netSettlementKrw = requireNotNull(netSettlementKrw()),
            carryForwardOutKrw = requireNotNull(carryForwardOutKrw),
            calculatedAt = requireNotNull(calculatedAt),
            confirmedAt = confirmedAt,
        )

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SettlementBatch was not found")

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, message)

    private fun unavailable(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message).also { cause?.let(it::initCause) }

    private fun dependency(
        message: String,
        cause: Throwable,
    ): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { it.initCause(cause) }

    private data class ItemSummary(
        val itemCount: Int,
        val grossPaidKrw: Long,
        val feeKrw: Long,
        val benefitCostKrw: Long,
        val netSettlementKrw: Long,
        val chunkCount: Int,
    )

    private data class AdjustmentSummary(
        val amountKrw: Long,
        val lastEffectiveAt: Instant?,
        val lastAdjustmentId: UUID?,
    )

    private companion object {
        const val CHUNK_SIZE = 500
        const val SYSTEM_ACTOR = "beanflow-settlement"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@Component
internal class SettlementBatchWorker(
    private val repository: SettlementBatchLifecycleRepository,
    private val service: SettlementBatchLifecycleService,
    private val clock: Clock,
    private val metrics: SettlementBatchMetrics,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.settlement.batch.fixed-delay-ms:86400000}",
        initialDelayString = "\${beanflow.settlement.batch.initial-delay-ms:3600000}",
    )
    fun run() {
        val now = clock.instant()
        val today = now.atZone(SEOUL).toLocalDate()
        repository.findOpenBatchIds(today, WORK_LIMIT).forEach { batchId ->
            try {
                service.calculate(batchId, now)
                service.confirm(batchId, now, "settlement-batch-worker")
            } catch (_: DomainFailure) {
                metrics.recordBatch(SettlementBatchState.OPEN, "RETRY_SCHEDULED")
            }
        }
    }

    private companion object {
        const val WORK_LIMIT = 100
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@Component
internal class SettlementBatchMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun recordBatch(
        state: SettlementBatchState,
        outcome: String,
        chunkCount: Int? = null,
    ) {
        meterRegistry.counter("beanflow.settlement.batch.count", "state", state.name, "outcome", outcome).increment()
        chunkCount?.let { meterRegistry.summary("beanflow.settlement.batch.chunk_count").record(it.toDouble()) }
    }
}
