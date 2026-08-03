package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.SettlementAdjustmentCreatedV1
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementItemOperations
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementItemView
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementBatchOperations
import io.github.kdh949.beanflow.settlement.api.ConfirmedSettlementBatchView
import io.github.kdh949.beanflow.settlement.api.CreateSettlementAdjustmentCommand
import io.github.kdh949.beanflow.settlement.api.SettlementAdjustmentOperations
import io.github.kdh949.beanflow.settlement.api.SettlementAdjustmentReasonCode
import io.github.kdh949.beanflow.settlement.api.SettlementAdjustmentResult
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class SettlementAdjustmentService(
    private val adjustments: SettlementAdjustmentJpaRepository,
    private val items: SettlementItemJpaRepository,
    private val batches: SettlementBatchJpaRepository,
    private val audits: AuditRecordOperations,
    private val financialEvents: FinancialEventPublicationOperations,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : SettlementAdjustmentOperations,
    ConfirmedSettlementItemOperations,
    ConfirmedSettlementBatchOperations {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun create(command: CreateSettlementAdjustmentCommand): SettlementAdjustmentResult =
        try {
            validate(command)
            adjustments.findByAdjustmentSource(command.adjustmentSource)?.let { existing ->
                if (!existing.matches(command)) sourceConflict("ADJUSTMENT_SOURCE_REUSED")
                metric(command.reasonCode, "REPLAYED")
                return existing.toResult()
            }
            val item = items.findById(command.settlementItemId).orElseThrow {
                DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, "Confirmed SettlementItem was not found")
            }
            val batch = batches.findLockedById(item.settlementBatchId) ?: unavailable("SettlementBatch was not found")
            if (batch.state != SettlementBatchState.CONFIRMED) {
                unavailable("SettlementAdjustment requires a confirmed SettlementBatch")
            }
            val createdAt = clock.instant()
            val adjustment =
                adjustments.saveAndFlush(
                    SettlementAdjustmentEntity(
                        id = identifierSource.next(),
                        storeId = item.storeId,
                        settlementItemId = item.id,
                        sourceSettlementBatchId = batch.id,
                        adjustmentSource = command.adjustmentSource,
                        reasonCode = command.reasonCode.toPersistenceReason(),
                        effectiveAt = command.effectiveAt,
                        orderCompletedAt = item.completedAt,
                        settlementDate = item.settlementDate,
                        currency = item.currency,
                        amountKrw = command.amountKrw,
                        createdAt = createdAt,
                    ),
                )
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = SYSTEM_ACTOR,
                        actorType = AuditActorType.SYSTEM,
                        action = "SETTLEMENT_ADJUSTMENT_CREATED",
                        targetType = "SETTLEMENT_ADJUSTMENT",
                        targetId = adjustment.id,
                        occurredAt = createdAt,
                        reason = adjustment.reasonCode.name,
                        afterSummary =
                            mapOf(
                                "settlementItemId" to adjustment.settlementItemId.toString(),
                                "sourceSettlementBatchId" to adjustment.sourceSettlementBatchId.toString(),
                                "settlementDate" to adjustment.settlementDate.toString(),
                                "amountKrw" to adjustment.amountKrw.toString(),
                                "currency" to adjustment.currency,
                            ),
                        correlationId = command.correlationId,
                        sourceReference = "settlement-adjustment:${adjustment.adjustmentSource}",
                    ),
                ),
            )
            financialEvents.publish(adjustment.toCreatedEvent(command.correlationId, identifierSource.next()))
            metric(command.reasonCode, "CREATED")
            adjustment.toResult()
        } catch (failure: DomainFailure) {
            metric(command.reasonCode, failure.code.name)
            throw failure
        } catch (failure: DataAccessException) {
            metric(command.reasonCode, "DEPENDENCY_UNAVAILABLE")
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "SettlementAdjustment persistence is unavailable",
            ).also { it.initCause(failure) }
        }

    @Transactional(readOnly = true)
    override fun findConfirmedItem(settlementItemId: UUID): ConfirmedSettlementItemView? {
        val item = items.findById(settlementItemId).orElse(null) ?: return null
        val batch = batches.findById(item.settlementBatchId).orElse(null) ?: return null
        if (batch.state != SettlementBatchState.CONFIRMED) return null
        return ConfirmedSettlementItemView(
            settlementItemId = item.id,
            settlementBatchId = batch.id,
            storeId = item.storeId,
            itemSource = item.itemSource,
            orderCompletedAt = item.completedAt,
            settlementDate = item.settlementDate,
            currency = item.currency,
            batchConfirmedAt = requireNotNull(batch.confirmedAt),
        )
    }

    @Transactional(readOnly = true)
    override fun findConfirmedBatch(settlementBatchId: UUID): ConfirmedSettlementBatchView? {
        val batch = batches.findById(settlementBatchId).orElse(null) ?: return null
        if (batch.state != SettlementBatchState.CONFIRMED) return null
        return ConfirmedSettlementBatchView(
            settlementBatchId = batch.id,
            settlementDate = batch.settlementDate,
            netSettlementKrw = requireNotNull(batch.netSettlementKrw()),
            currency = "KRW",
            confirmedAt = requireNotNull(batch.confirmedAt),
        )
    }

    private fun validate(command: CreateSettlementAdjustmentCommand) {
        if (command.adjustmentSource.isBlank() || command.adjustmentSource != command.adjustmentSource.trim() ||
            command.adjustmentSource.length > 240 || command.correlationId.isBlank() ||
            command.correlationId != command.correlationId.trim() || command.correlationId.length > 240
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "SettlementAdjustment command is invalid")
        }
    }

    private fun SettlementAdjustmentEntity.matches(command: CreateSettlementAdjustmentCommand): Boolean =
        settlementItemId == command.settlementItemId && adjustmentSource == command.adjustmentSource &&
            reasonCode.name == command.reasonCode.name && effectiveAt == command.effectiveAt &&
            amountKrw == command.amountKrw

    private fun SettlementAdjustmentEntity.toCreatedEvent(
        correlationId: String,
        eventId: UUID,
    ): SettlementAdjustmentCreatedV1 =
        SettlementAdjustmentCreatedV1(
            envelope =
                EventEnvelope(
                    eventId = eventId,
                    eventType = "SettlementAdjustmentCreatedV1",
                    aggregateId = id,
                    aggregateVersion = 0,
                    occurredAt = effectiveAt,
                    payloadVersion = 1,
                    correlationId = correlationId,
                    causationId = "settlement-adjustment:$adjustmentSource",
                ),
            settlementAdjustmentId = id,
            adjustmentSource = adjustmentSource,
            settlementItemId = settlementItemId,
            settlementBatchId = sourceSettlementBatchId,
            reasonCode = reasonCode.name,
            effectiveAt = effectiveAt,
            orderCompletedAt = orderCompletedAt,
            settlementDate = settlementDate,
            currency = currency,
            amountKrw = amountKrw,
        )

    private fun SettlementAdjustmentEntity.toResult(): SettlementAdjustmentResult =
        SettlementAdjustmentResult(
            settlementAdjustmentId = id,
            settlementItemId = settlementItemId,
            sourceSettlementBatchId = sourceSettlementBatchId,
            storeId = storeId,
            adjustmentSource = adjustmentSource,
            reasonCode = SettlementAdjustmentReasonCode.valueOf(reasonCode.name),
            effectiveAt = effectiveAt,
            orderCompletedAt = orderCompletedAt,
            settlementDate = settlementDate,
            currency = currency,
            amountKrw = amountKrw,
        )

    private fun SettlementAdjustmentReasonCode.toPersistenceReason(): SettlementAdjustmentReason =
        SettlementAdjustmentReason.valueOf(name)

    private fun metric(
        reasonCode: SettlementAdjustmentReasonCode,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.settlement.adjustment.count",
                "reason_code",
                reasonCode.name,
                "outcome",
                outcome,
            ).increment()
    }

    private fun sourceConflict(reason: String): Nothing =
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, "SETTLEMENT_SOURCE_CONFLICT: $reason")

    private fun unavailable(message: String): Nothing =
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message)

    private companion object {
        const val SYSTEM_ACTOR = "beanflow-settlement"
    }
}
