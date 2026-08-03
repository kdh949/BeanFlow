package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.InspectPaymentCancellationSetupCommand
import io.github.kdh949.beanflow.operations.api.PaymentCancellationSetupIntegrityOperations
import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.api.OrderCancellationSettlementEvidence
import io.github.kdh949.beanflow.ordering.api.OrderCancellationSettlementEvidenceOperations
import io.github.kdh949.beanflow.payment.api.CustomerCancellationRefundEvidence
import io.github.kdh949.beanflow.payment.api.CustomerCancellationRefundEvidenceOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
internal class CustomerCancellationRefundExclusionService(
    private val orders: OrderCancellationSettlementEvidenceOperations,
    private val refunds: CustomerCancellationRefundEvidenceOperations,
    private val items: SettlementItemJpaRepository,
    private val auditRecords: AuditRecordOperations,
    private val auditRecordQueries: AuditRecordQueryOperations,
    private val setupIntegrity: PaymentCancellationSetupIntegrityOperations,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun exclude(
        event: PaymentRefundedV1,
        processedAt: Instant,
    ) {
        validateEventShape(event)
        try {
            val order =
                orders.find(event.orderId)
                    ?: conflict("ORDER_MISSING", "Customer-cancellation Order evidence is missing")
            validateOrder(event, order)
            val refund =
                refunds.find(event.refundId)
                    ?: conflict("REFUND_MISSING", "Customer-cancellation Refund evidence is missing")
            validateRefund(event, refund)
            if (items.findByOrderId(event.orderId) != null) {
                conflict("SETTLEMENT_ITEM_EXISTS", "Cancelled Order unexpectedly has a SettlementItem")
            }
        } catch (failure: DomainFailure) {
            setupIntegrity.inspect(
                InspectPaymentCancellationSetupCommand(
                    orderId = event.orderId,
                    now = processedAt,
                ),
            )
            throw failure
        }
        val setupIssue =
            setupIntegrity.inspect(
                InspectPaymentCancellationSetupCommand(
                    orderId = event.orderId,
                    now = processedAt,
                ),
            )
        if (setupIssue != null) {
            conflict("PAYMENT_SETUP_INCOMPLETE", "Customer-cancellation payment setup is incomplete")
        }

        val auditKey =
            AuditRecordKey(
                action = AUDIT_ACTION,
                targetType = AUDIT_TARGET_TYPE,
                targetId = event.refundId,
                sourceReference = event.refundSource,
            )
        if (!auditRecordQueries.exists(auditKey)) {
            auditRecords.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = SYSTEM_ACTOR,
                        actorType = AuditActorType.SYSTEM,
                        action = AUDIT_ACTION,
                        targetType = AUDIT_TARGET_TYPE,
                        targetId = event.refundId,
                        occurredAt = processedAt,
                        reason = AUDIT_REASON,
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
                AUDIT_REASON,
            ).increment()
    }

    private fun validateEventShape(event: PaymentRefundedV1) {
        if (event.completionDisposition != RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION) {
            conflict("UNSUPPORTED_DISPOSITION", "Refund is not a pre-acceptance cancellation")
        }
        val envelope = event.envelope
        if (envelope.eventType != EVENT_TYPE || envelope.payloadVersion != 1 ||
            envelope.aggregateId != event.refundId || envelope.occurredAt != event.refundSucceededAt ||
            envelope.causationId != "refund:${event.refundId}:succeeded" || envelope.correlationId.isBlank() ||
            event.currency != KRW || event.cashRefundedKrw <= 0 || event.orderCompletedAt != null ||
            event.settlementDate != null || event.settlementItemSource != null ||
            event.settlementRefundEffect != null || event.refundSource.isBlank()
        ) {
            conflict("EVENT_CONTRACT", "PaymentRefundedV1 cancellation payload is inconsistent")
        }
    }

    private fun validateOrder(
        event: PaymentRefundedV1,
        order: OrderCancellationSettlementEvidence,
    ) {
        if (order.state != CANCELLED || order.cancelledAt == null) {
            conflict("ORDER_NOT_CANCELLED", "Refund Order is not durably cancelled")
        }
        if (order.cancellationCause != OrderCancellationCause.CUSTOMER_REQUEST) {
            conflict("ORDER_CAUSE", "Refund Order is not a customer-request cancellation")
        }
        if (order.customerId != event.customerId) {
            conflict("ORDER_CUSTOMER", "Refund customer does not match its Order")
        }
        if (listOf(order.acceptedAt, order.preparingAt, order.readyAt, order.completedAt).any { it != null }) {
            conflict("ORDER_LIFECYCLE", "Refund Order passed the pre-acceptance boundary")
        }
        if (order.cancelledAt.isAfter(event.refundSucceededAt)) {
            conflict("ORDER_REFUND_CHRONOLOGY", "Refund succeeded before Order cancellation")
        }
        val expectedSource = "order:${event.orderId}:customer-cancellation:${order.aggregateVersion}:payment"
        if (event.refundSource != expectedSource) {
            conflict("ORDER_SOURCE", "Refund source does not match the terminal Order version")
        }
    }

    private fun validateRefund(
        event: PaymentRefundedV1,
        refund: CustomerCancellationRefundEvidence,
    ) {
        if (refund.orderId != event.orderId) {
            conflict("REFUND_ORDER", "Refund does not belong to the event Order")
        }
        if (!refund.succeeded || refund.succeededAt != event.refundSucceededAt) {
            conflict("REFUND_STATE", "Refund is not durably succeeded at the event time")
        }
        if (refund.reason != REFUND_REASON) {
            conflict("REFUND_REASON", "Refund reason is not customer Order cancellation")
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
        const val KRW = "KRW"
        const val CANCELLED = "CANCELLED"
        const val REFUND_REASON = "CUSTOMER_ORDER_CANCELLED"
        const val AUDIT_ACTION = "SETTLEMENT_REFUND_EXCLUDED"
        const val AUDIT_TARGET_TYPE = "REFUND"
        const val AUDIT_REASON = "ORDER_NOT_COMPLETED_CUSTOMER_CANCELLATION"
        const val SYSTEM_ACTOR = "beanflow-settlement"
    }
}

@Component
internal class PaymentRefundedSettlementListener(
    private val service: CustomerCancellationRefundExclusionService,
    private val clock: Clock,
) {
    @ApplicationModuleListener(id = "beanflow.settlement.payment-refunded-v1")
    fun on(event: PaymentRefundedV1) {
        service.exclude(event, clock.instant())
    }
}
