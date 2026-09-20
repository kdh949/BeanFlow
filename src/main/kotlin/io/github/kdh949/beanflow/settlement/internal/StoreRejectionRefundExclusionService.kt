package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.ordering.api.OrderRejectionCause
import io.github.kdh949.beanflow.ordering.api.OrderRejectionSettlementEvidence
import io.github.kdh949.beanflow.ordering.api.OrderRejectionSettlementEvidenceOperations
import io.github.kdh949.beanflow.ordering.api.OrderRejectionSourceActorType
import io.github.kdh949.beanflow.payment.api.StoreRejectionRefundEvidence
import io.github.kdh949.beanflow.payment.api.StoreRejectionRefundEvidenceOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class StoreRejectionRefundExclusionService(
    private val orders: OrderRejectionSettlementEvidenceOperations,
    private val refunds: StoreRejectionRefundEvidenceOperations,
    private val items: SettlementItemJpaRepository,
    private val adjustments: SettlementAdjustmentJpaRepository,
    private val auditRecords: AuditRecordOperations,
    private val auditRecordQueries: AuditRecordQueryOperations,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun exclude(
        event: PaymentRefundedV1,
        processedAt: Instant,
    ) {
        validateEventShape(event)
        val order =
            orders.find(event.orderId)
                ?: conflict("ORDER_MISSING", "Store-rejection Order evidence is missing")
        val auditReason = validateOrder(event, order)
        val refund =
            refunds.find(event.refundId)
                ?: conflict("REFUND_MISSING", "Store-rejection Refund evidence is missing")
        validateRefund(event, refund)
        if (items.findByOrderId(event.orderId) != null) {
            conflict("SETTLEMENT_ITEM_EXISTS", "Rejected Order unexpectedly has a SettlementItem")
        }
        if (adjustments.findByAdjustmentSource(event.refundSource) != null) {
            conflict("SETTLEMENT_ADJUSTMENT_EXISTS", "Rejected Order unexpectedly has a SettlementAdjustment")
        }

        val auditKey =
            AuditRecordKey(
                action = AUDIT_ACTION,
                targetType = AUDIT_TARGET_TYPE,
                targetId = event.refundId,
                sourceReference = event.refundSource,
            )
        val existingAudit = auditRecordQueries.find(auditKey)
        if (existingAudit != null && existingAudit.reason != auditReason) {
            conflict("AUDIT_REASON", "Store-rejection exclusion Audit reason conflicts with Order cause")
        }
        if (existingAudit == null) {
            auditRecords.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = SYSTEM_ACTOR,
                        actorType = AuditActorType.SYSTEM,
                        category = AuditCategory.SETTLEMENT_AND_DISPUTE,
                        action = AUDIT_ACTION,
                        targetType = AUDIT_TARGET_TYPE,
                        targetId = event.refundId,
                        occurredAt = processedAt,
                        reason = auditReason,
                        beforeSummary = mapOf("settlementItemExists" to "false"),
                        afterSummary = mapOf("settlementDisposition" to "NOT_APPLICABLE"),
                        correlationId = event.envelope.correlationId,
                        sourceReference = event.refundSource,
                    ),
                ),
            )
        }
        meterRegistry
            .counter(
                "beanflow.settlement.refund.disposition.count",
                "disposition",
                "NOT_APPLICABLE",
                "reason",
                auditReason,
            ).increment()
    }

    private fun validateEventShape(event: PaymentRefundedV1) {
        val envelope = event.envelope
        if (event.completionDisposition != RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION ||
            envelope.eventType != EVENT_TYPE || envelope.payloadVersion != PAYLOAD_VERSION ||
            envelope.aggregateId != event.refundId || envelope.occurredAt != event.refundSucceededAt ||
            envelope.causationId != "refund:${event.refundId}:succeeded" || envelope.correlationId.isBlank() ||
            event.currency != KRW || event.cashRefundedKrw <= 0 || event.orderCompletedAt != null ||
            event.settlementDate != null || event.settlementItemSource != null ||
            event.settlementRefundEffect != null || event.refundSource.isBlank()
        ) {
            conflict("EVENT_CONTRACT", "PaymentRefundedV1 store-rejection payload is inconsistent")
        }
    }

    private fun validateOrder(
        event: PaymentRefundedV1,
        order: OrderRejectionSettlementEvidence,
    ): String {
        if (order.state != REJECTED || order.rejectedAt == null) {
            conflict("ORDER_NOT_REJECTED", "Refund Order is not durably rejected")
        }
        if (order.customerId != event.customerId) {
            conflict("ORDER_CUSTOMER", "Refund customer does not match its Order")
        }
        if (listOf(order.acceptedAt, order.preparingAt, order.readyAt, order.completedAt).any { it != null }) {
            conflict("ORDER_LIFECYCLE", "Refund Order passed the pre-acceptance boundary")
        }
        val terminalVersion =
            order.rejectionTerminalVersion
                ?: conflict("ORDER_VERSION_MISSING", "Order rejection terminal version is missing")
        if (terminalVersion != order.aggregateVersion) {
            conflict("ORDER_VERSION", "Order rejection terminal version does not match durable Order version")
        }
        val eventId =
            order.rejectionEventId
                ?: conflict("ORDER_EVENT_MISSING", "Order rejection event source is missing")
        if (event.refundSource != "event:$eventId:payment-refund") {
            conflict("ORDER_SOURCE", "Refund source does not match the OrderRejectedV1 event")
        }
        if (order.rejectedAt.isAfter(event.refundSucceededAt)) {
            conflict("ORDER_REFUND_CHRONOLOGY", "Refund succeeded before Order rejection")
        }
        return when (order.rejectionCause) {
            OrderRejectionCause.STORE_REJECTION -> {
                if (order.rejectionActorType != OrderRejectionSourceActorType.STORE_OWNER &&
                    order.rejectionActorType != OrderRejectionSourceActorType.STORE_STAFF
                ) {
                    conflict("ORDER_ACTOR", "Store rejection actor does not match its cause")
                }
                STORE_REJECTION_AUDIT_REASON
            }

            OrderRejectionCause.ACCEPTANCE_TIMEOUT -> {
                if (order.rejectionActorType != OrderRejectionSourceActorType.SYSTEM_TIMEOUT) {
                    conflict("ORDER_ACTOR", "Acceptance timeout actor does not match its cause")
                }
                ACCEPTANCE_TIMEOUT_AUDIT_REASON
            }

            null -> {
                conflict("ORDER_CAUSE_MISSING", "Order rejection cause is missing")
            }
        }
    }

    private fun validateRefund(
        event: PaymentRefundedV1,
        refund: StoreRejectionRefundEvidence,
    ) {
        if (refund.orderId != event.orderId) {
            conflict("REFUND_ORDER", "Refund does not belong to the event Order")
        }
        if (!refund.succeeded || refund.succeededAt != event.refundSucceededAt) {
            conflict("REFUND_STATE", "Refund is not durably succeeded at the event time")
        }
        if (refund.reason != REFUND_REASON) {
            conflict("REFUND_REASON", "Refund reason is not store Order rejection")
        }
        if (refund.sourceReference != event.refundSource) {
            conflict("REFUND_SOURCE", "Refund source does not match the event")
        }
        if (refund.requestedAmountKrw != event.cashRefundedKrw ||
            refund.succeededAmountKrw != event.cashRefundedKrw
        ) {
            conflict("REFUND_AMOUNT", "Refund amount does not match the event")
        }
        if (event.envelope.aggregateVersion != refund.aggregateVersion) {
            conflict("REFUND_VERSION", "Refund event version does not match durable Refund state")
        }
    }

    private fun conflict(
        reason: String,
        message: String,
    ): Nothing {
        meterRegistry
            .counter(
                "beanflow.settlement.refund.exclusion_conflict.count",
                "reason",
                reason,
            ).increment()
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, "SETTLEMENT_SOURCE_CONFLICT: $message")
    }

    private companion object {
        const val EVENT_TYPE = "PaymentRefundedV1"
        const val PAYLOAD_VERSION = 1
        const val KRW = "KRW"
        const val REJECTED = "REJECTED"
        const val REFUND_REASON = "STORE_ORDER_REJECTED"
        const val AUDIT_ACTION = "SETTLEMENT_REFUND_EXCLUDED"
        const val AUDIT_TARGET_TYPE = "REFUND"
        const val STORE_REJECTION_AUDIT_REASON = "ORDER_NOT_COMPLETED_STORE_REJECTION"
        const val ACCEPTANCE_TIMEOUT_AUDIT_REASON = "ORDER_NOT_COMPLETED_ACCEPTANCE_TIMEOUT"
        const val SYSTEM_ACTOR = "beanflow-settlement"
    }
}

@Service
internal class PreAcceptanceRefundExclusionRouter(
    private val orders: OrderRejectionSettlementEvidenceOperations,
    private val customerCancellations: CustomerCancellationRefundExclusionService,
    private val storeRejections: StoreRejectionRefundExclusionService,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun exclude(
        event: PaymentRefundedV1,
        processedAt: Instant,
    ) {
        when (orders.find(event.orderId)?.state) {
            "CANCELLED" -> customerCancellations.exclude(event, processedAt)
            else -> storeRejections.exclude(event, processedAt)
        }
    }
}
