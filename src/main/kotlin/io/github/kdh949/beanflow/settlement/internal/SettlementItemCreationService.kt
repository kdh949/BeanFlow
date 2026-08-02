package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2Contract
import io.github.kdh949.beanflow.eventing.api.SettlementItemCreatedV1
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OpenReprocessingCaseCommand
import io.github.kdh949.beanflow.operations.api.SettlementLateItemReprocessingCaseOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Component
internal class SettlementBatchPersistence(
    private val jdbcTemplate: JdbcTemplate,
    private val repository: SettlementBatchJpaRepository,
    private val identifierSource: IdentifierSource,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun openOrLock(
        storeId: UUID,
        settlementDate: LocalDate,
        createdAt: Instant,
    ): SettlementBatchEntity {
        jdbcTemplate.update(
            """
            INSERT INTO settlement_batch (
                id, store_id, settlement_date, state, created_at, version
            ) VALUES (?, ?, ?, 'OPEN', ?, 0)
            ON CONFLICT (store_id, settlement_date) DO NOTHING
            """.trimIndent(),
            identifierSource.next(),
            storeId,
            settlementDate,
            Timestamp.from(createdAt),
        )
        return repository.findLockedByStoreIdAndSettlementDate(storeId, settlementDate)
            ?: unavailable("SettlementBatch insert-or-read did not produce a durable batch")
    }

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

@Service
internal class SettlementItemCreationService(
    private val batchPersistence: SettlementBatchPersistence,
    private val itemRepository: SettlementItemJpaRepository,
    private val auditRecords: AuditRecordOperations,
    private val financialEvents: FinancialEventPublicationOperations,
    private val lateItems: SettlementLateItemReprocessingCaseOperations,
    private val identifierSource: IdentifierSource,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun create(
        event: OrderCompletedV2,
        processedAt: Instant,
    ): UUID {
        OrderCompletedV2Contract.validate(event)
        itemRepository.findByItemSource(event.completionSource)?.let { existing ->
            if (!existing.matches(event)) {
                unavailable("SettlementItem source was reused with a different completion snapshot")
            }
            return existing.id
        }
        itemRepository.findByOrderId(event.orderId)?.let {
            unavailable("Completed Order already belongs to a different SettlementItem source")
        }

        val batch = batchPersistence.openOrLock(event.storeId, event.settlementDate, processedAt)
        if (batch.state != SettlementBatchState.OPEN) {
            lateItems.openLateItemCase(
                OpenReprocessingCaseCommand(
                    ownerReference = "settlement-late-item:${event.completionSource}",
                    reason = "BATCH_${batch.state.name}_DOES_NOT_ACCEPT_LATE_ITEM",
                    correlationId = event.envelope.correlationId,
                    now = processedAt,
                ),
            )
            unavailable("Closed SettlementBatch requires manual late-item reprocessing")
        }
        batch.requireAcceptingItems(event.storeId, event.settlementDate)

        val item =
            itemRepository.saveAndFlush(
                SettlementItemEntity(
                    id = identifierSource.next(),
                    settlementBatchId = batch.id,
                    orderId = event.orderId,
                    storeId = event.storeId,
                    itemSource = event.completionSource,
                    completedAt = event.completedAt,
                    settlementDate = event.settlementDate,
                    currency = event.currency,
                    grossPaidKrw = event.grossPaidKrw,
                    feeRateBps = event.feeRateBps,
                    feeKrw = event.feeKrw,
                    couponCostKrw = event.couponCostKrw,
                    pointCostKrw = event.pointCostKrw,
                    benefitCostKrw = event.benefitCostKrw,
                    netSettlementKrw = event.netSettlementKrw,
                    createdAt = processedAt,
                ),
            )
        val sourceReference = "settlement-item:${event.completionSource}"
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = SYSTEM_ACTOR,
                    actorType = AuditActorType.SYSTEM,
                    action = "SETTLEMENT_ITEM_CREATED",
                    targetType = "SETTLEMENT_ITEM",
                    targetId = item.id,
                    occurredAt = processedAt,
                    reason = OrderCompletedV2Contract.EVENT_TYPE,
                    afterSummary =
                        mapOf(
                            "settlementBatchId" to batch.id.toString(),
                            "itemSource" to item.itemSource,
                            "settlementDate" to item.settlementDate.toString(),
                            "currency" to item.currency,
                            "grossPaidKrw" to item.grossPaidKrw.toString(),
                            "feeKrw" to item.feeKrw.toString(),
                            "benefitCostKrw" to item.benefitCostKrw.toString(),
                            "netSettlementKrw" to item.netSettlementKrw.toString(),
                        ),
                    correlationId = event.envelope.correlationId,
                    sourceReference = sourceReference,
                ),
            ),
        )
        financialEvents.publish(item.toCreatedEvent(event, processedAt, identifierSource.next()))
        return item.id
    }

    private fun SettlementItemEntity.matches(event: OrderCompletedV2): Boolean =
        orderId == event.orderId && storeId == event.storeId && itemSource == event.completionSource &&
            completedAt == event.completedAt && settlementDate == event.settlementDate && currency == event.currency &&
            grossPaidKrw == event.grossPaidKrw && feeRateBps == event.feeRateBps && feeKrw == event.feeKrw &&
            couponCostKrw == event.couponCostKrw && pointCostKrw == event.pointCostKrw &&
            benefitCostKrw == event.benefitCostKrw && netSettlementKrw == event.netSettlementKrw

    private fun SettlementItemEntity.toCreatedEvent(
        source: OrderCompletedV2,
        occurredAt: Instant,
        eventId: UUID,
    ): SettlementItemCreatedV1 =
        SettlementItemCreatedV1(
            envelope =
                EventEnvelope(
                    eventId = eventId,
                    eventType = "SettlementItemCreatedV1",
                    aggregateId = id,
                    aggregateVersion = 0,
                    occurredAt = occurredAt,
                    payloadVersion = 1,
                    correlationId = source.envelope.correlationId,
                    causationId = "settlement-item:$itemSource",
                ),
            settlementItemId = id,
            settlementBatchId = settlementBatchId,
            itemSource = itemSource,
            orderId = orderId,
            storeId = storeId,
            completedAt = completedAt,
            settlementDate = settlementDate,
            currency = currency,
            grossPaidKrw = grossPaidKrw,
            feeKrw = feeKrw,
            benefitCostKrw = benefitCostKrw,
            netSettlementKrw = netSettlementKrw,
        )

    private fun unavailable(message: String): Nothing = throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message)

    private companion object {
        const val SYSTEM_ACTOR = "beanflow-settlement"
    }
}

@Component
internal class OrderCompletedSettlementListener(
    private val service: SettlementItemCreationService,
    private val clock: Clock,
) {
    @ApplicationModuleListener(id = "beanflow.settlement.order-completed-v2")
    fun on(event: OrderCompletedV2) {
        service.create(event, clock.instant())
    }
}
