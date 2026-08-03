package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundDelayedV1
import io.github.kdh949.beanflow.eventing.api.CustomerCancellationRefundSucceededV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.FinancialEventPublicationOperations
import io.github.kdh949.beanflow.eventing.api.PaymentRefundedV1
import io.github.kdh949.beanflow.eventing.api.PointsAccruedV1
import io.github.kdh949.beanflow.eventing.api.PointsRestoredV1
import io.github.kdh949.beanflow.eventing.api.RefundCompletionDisposition
import io.github.kdh949.beanflow.eventing.api.SettlementItemCreatedV1
import io.github.kdh949.beanflow.eventing.api.SettlementAdjustmentCreatedV1
import io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
internal class FinancialEventPublicationService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val validator: FinancialEventValidator,
    private val meterRegistry: MeterRegistry,
) : FinancialEventPublicationOperations {
    override fun publish(event: PaymentRefundedV1) {
        validator.validate(event)
        persist(event, PAYMENT_REFUNDED_TARGETS, event.envelope)
    }

    override fun publish(event: PointsAccruedV1) {
        validator.validate(event)
        persist(event, POINTS_ACCRUED_TARGETS, event.envelope)
    }

    override fun publish(event: PointsRestoredV1) {
        validator.validate(event)
        persist(event, POINTS_RESTORED_TARGETS, event.envelope)
    }

    override fun publish(event: SettlementItemCreatedV1) {
        validator.validate(event)
        persist(event, SETTLEMENT_ITEM_CREATED_TARGETS, event.envelope)
    }

    override fun publish(event: SettlementBatchConfirmedV1) {
        validator.validate(event)
        persist(event, SETTLEMENT_BATCH_CONFIRMED_TARGETS, event.envelope)
    }

    override fun publish(event: SettlementAdjustmentCreatedV1) {
        validator.validate(event)
        persist(event, SETTLEMENT_ADJUSTMENT_CREATED_TARGETS, event.envelope)
    }

    override fun publish(event: CustomerCancellationRefundSucceededV1) {
        validator.validate(event)
        persist(event, CUSTOMER_CANCELLATION_REFUND_SUCCEEDED_TARGETS, event.envelope)
    }

    override fun publish(event: CustomerCancellationRefundDelayedV1) {
        validator.validate(event)
        persist(event, CUSTOMER_CANCELLATION_REFUND_DELAYED_TARGETS, event.envelope)
    }

    private fun persist(
        event: Any,
        targets: List<String>,
        envelope: EventEnvelope,
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            unavailable("Financial event publication requires its owner transaction")
        }
        try {
            val serializedEvent = objectMapper.writeValueAsString(event)
            targets.forEach { target ->
                val inserted =
                    jdbcTemplate.update(
                        """
                        INSERT INTO event_publication (
                            id, listener_id, event_type, serialized_event, publication_date,
                            completion_date, status, completion_attempts, last_resubmission_date
                        ) VALUES (?, ?, ?, ?, ?, NULL, 'FAILED', 0, NULL)
                        """.trimIndent(),
                        UUID.randomUUID(),
                        target,
                        event.javaClass.name,
                        serializedEvent,
                        Timestamp.from(envelope.occurredAt),
                    )
                if (inserted != 1) {
                    unavailable("Financial event publication target was not persisted")
                }
            }
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        metric(
                            envelope.eventType,
                            envelope.payloadVersion,
                            if (status == TransactionSynchronization.STATUS_COMMITTED) "COMMITTED" else "ROLLED_BACK",
                        )
                    }
                },
            )
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            metric(envelope.eventType, envelope.payloadVersion, "FAILED")
            unavailable("Financial event publication could not be persisted", failure)
        }
    }

    private fun metric(
        eventType: String,
        payloadVersion: Int,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "beanflow.event.contract.producer.count",
                "event_type",
                eventType,
                "payload_version",
                payloadVersion.toString(),
                "outcome",
                outcome,
            ).increment()
    }

    private fun unavailable(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { cause?.let(it::initCause) }

    private companion object {
        val PAYMENT_REFUNDED_TARGETS =
            listOf(
                "beanflow.settlement.payment-refunded-v1",
                "beanflow.analytics.payment-refunded-v1",
            )
        val POINTS_ACCRUED_TARGETS = listOf("beanflow.analytics.points-accrued-v1")
        val POINTS_RESTORED_TARGETS = listOf("beanflow.analytics.points-restored-v1")
        val SETTLEMENT_ITEM_CREATED_TARGETS = listOf("beanflow.analytics.settlement-item-created-v1")
        val SETTLEMENT_BATCH_CONFIRMED_TARGETS = listOf("beanflow.dispute.settlement-batch-confirmed-v1")
        val SETTLEMENT_ADJUSTMENT_CREATED_TARGETS = listOf("beanflow.analytics.settlement-adjustment-created-v1")
        val CUSTOMER_CANCELLATION_REFUND_SUCCEEDED_TARGETS =
            listOf("beanflow.notification.customer-cancellation-refund-succeeded-v1")
        val CUSTOMER_CANCELLATION_REFUND_DELAYED_TARGETS =
            listOf("beanflow.notification.customer-cancellation-refund-delayed-v1")
    }
}

@Service
internal class FinancialEventValidator {
    fun validate(event: CustomerCancellationRefundSucceededV1) {
        validateCustomerCancellationRefundEvent(
            event.envelope,
            event.orderId,
            event.customerId,
            event.orderAggregateVersion,
            event.refundAmountKrw,
            event.outcomeAt,
            CUSTOMER_CANCELLATION_REFUND_SUCCEEDED,
            "succeeded",
        )
    }

    fun validate(event: CustomerCancellationRefundDelayedV1) {
        validateCustomerCancellationRefundEvent(
            event.envelope,
            event.orderId,
            event.customerId,
            event.orderAggregateVersion,
            event.refundAmountKrw,
            event.outcomeAt,
            CUSTOMER_CANCELLATION_REFUND_DELAYED,
            "delayed",
        )
    }

    fun validate(event: PaymentRefundedV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == PAYMENT_REFUNDED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.aggregateId == event.refundId &&
                event.envelope.occurredAt == event.refundSucceededAt &&
                event.envelope.causationId == "refund:${event.refundId}:succeeded",
        )
        if (event.refundSource.isBlank() || event.currency != KRW || event.cashRefundedKrw < 0) {
            invalid("PaymentRefundedV1 required fields are invalid")
        }
        when (event.completionDisposition) {
            RefundCompletionDisposition.COMPLETED_ORDER -> {
                if (event.orderCompletedAt == null || event.settlementDate == null ||
                    event.settlementDate != event.orderCompletedAt.atZone(SEOUL).toLocalDate() ||
                    event.settlementItemSource !=
                    "order:${event.orderId}:completed:${completionVersion(event.settlementItemSource)}" ||
                    event.settlementRefundEffect == null
                ) {
                    invalid("Completed PaymentRefundedV1 is missing its immutable completion input")
                }
            }

            RefundCompletionDisposition.PRE_COMPLETION_ORDER -> {
                if (event.orderCompletedAt != null || event.settlementDate != null ||
                    event.settlementItemSource != null || event.settlementRefundEffect == null
                ) {
                    invalid("Pre-completion PaymentRefundedV1 contains invalid completion input")
                }
            }

            RefundCompletionDisposition.PRE_ACCEPTANCE_CANCELLATION -> {
                if (event.orderCompletedAt != null || event.settlementDate != null ||
                    event.settlementItemSource != null || event.settlementRefundEffect != null
                ) {
                    invalid("Pre-acceptance PaymentRefundedV1 must not contain settlement input")
                }
            }
        }
        event.settlementRefundEffect?.let { effect ->
            if (listOf(
                    effect.grossPaidDeltaKrw,
                    effect.feeDeltaKrw,
                    effect.benefitCostDeltaKrw,
                    effect.netSettlementDeltaKrw,
                ).any { it > 0 } ||
                effect.netSettlementDeltaKrw !=
                exactSubtract(
                    exactSubtract(effect.grossPaidDeltaKrw, effect.feeDeltaKrw),
                    effect.benefitCostDeltaKrw,
                )
            ) {
                invalid("PaymentRefundedV1 settlement effect does not tie out")
            }
        }
    }

    fun validate(event: PointsAccruedV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == POINTS_ACCRUED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.occurredAt == event.orderCompletedAt &&
                event.envelope.causationId == "point-transaction:${event.pointTransactionSource}",
        )
        if (event.pointTransactionSource.isBlank() || event.orderCompletionSource.isBlank() ||
            event.amountKrw < 0 || event.currency != KRW
        ) {
            invalid("PointsAccruedV1 required fields are invalid")
        }
    }

    fun validate(event: PointsRestoredV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == POINTS_RESTORED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.occurredAt == event.refundSucceededAt &&
                event.envelope.causationId == "point-transaction:${event.pointTransactionSource}",
        )
        if (event.pointTransactionSource.isBlank() || event.refundSource.isBlank() ||
            event.amountKrw < 0 || event.currency != KRW
        ) {
            invalid("PointsRestoredV1 required fields are invalid")
        }
    }

    fun validate(event: SettlementItemCreatedV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == SETTLEMENT_ITEM_CREATED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.aggregateId == event.settlementItemId &&
                event.envelope.aggregateVersion == 0L &&
                event.envelope.causationId == "settlement-item:${event.itemSource}",
        )
        if (event.settlementItemId == ZERO_UUID || event.settlementBatchId == ZERO_UUID ||
            event.orderId == ZERO_UUID || event.storeId == ZERO_UUID || event.itemSource.isBlank() ||
            event.itemSource != event.itemSource.trim() || event.itemSource.length > 240 ||
            event.settlementDate != event.completedAt.atZone(SEOUL).toLocalDate() || event.currency != KRW ||
            listOf(event.grossPaidKrw, event.feeKrw, event.benefitCostKrw, event.netSettlementKrw).any { it < 0 } ||
            event.netSettlementKrw !=
            exactSubtract(exactSubtract(event.grossPaidKrw, event.feeKrw), event.benefitCostKrw)
        ) {
            invalid("SettlementItemCreatedV1 required fields do not tie out")
        }
    }

    fun validate(event: SettlementBatchConfirmedV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == SETTLEMENT_BATCH_CONFIRMED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.aggregateId == event.settlementBatchId &&
                event.envelope.causationId == "settlement-batch:${event.settlementBatchId}:confirmed" &&
                event.envelope.occurredAt.toString().isNotBlank(),
        )
        if (event.state != "CONFIRMED" || event.currency != KRW) {
            invalid("SettlementBatchConfirmedV1 required fields are invalid")
        }
    }

    fun validate(event: SettlementAdjustmentCreatedV1) {
        validateEnvelope(
            event.envelope,
            event.envelope.eventType == SETTLEMENT_ADJUSTMENT_CREATED &&
                event.envelope.payloadVersion == 1 &&
                event.envelope.aggregateId == event.settlementAdjustmentId &&
                event.envelope.aggregateVersion == 0L &&
                event.envelope.occurredAt == event.effectiveAt &&
                event.envelope.causationId == "settlement-adjustment:${event.adjustmentSource}",
        )
        if (event.adjustmentSource.isBlank() || event.adjustmentSource != event.adjustmentSource.trim() ||
            event.adjustmentSource.length > 240 || event.reasonCode !in SETTLEMENT_ADJUSTMENT_REASONS ||
            event.currency != KRW ||
            event.settlementDate != event.orderCompletedAt.atZone(SEOUL).toLocalDate()
        ) {
            invalid("SettlementAdjustmentCreatedV1 required fields are invalid")
        }
    }

    private fun validateEnvelope(
        envelope: EventEnvelope,
        eventSpecificValid: Boolean,
    ) {
        if (!eventSpecificValid || envelope.eventId == ZERO_UUID || envelope.aggregateId == ZERO_UUID ||
            envelope.aggregateVersion < 0 || envelope.correlationId.isBlank() || envelope.causationId.isBlank()
        ) {
            invalid("Financial event envelope is invalid")
        }
    }

    @Suppress("LongParameterList")
    private fun validateCustomerCancellationRefundEvent(
        envelope: EventEnvelope,
        orderId: UUID,
        customerId: UUID,
        orderAggregateVersion: Long,
        refundAmountKrw: Long,
        outcomeAt: Instant,
        eventType: String,
        outcome: String,
    ) {
        validateEnvelope(
            envelope,
            envelope.eventType == eventType &&
                envelope.payloadVersion == 1 &&
                envelope.occurredAt == outcomeAt &&
                envelope.causationId == "refund:${envelope.aggregateId}:customer-cancellation:$outcome",
        )
        if (orderId == ZERO_UUID || customerId == ZERO_UUID || orderAggregateVersion < 0 || refundAmountKrw <= 0) {
            invalid("Customer cancellation Refund event fields are invalid")
        }
    }

    private fun completionVersion(source: String?): Long? = source?.substringAfterLast(':')?.toLongOrNull()

    private fun exactSubtract(
        left: Long,
        right: Long,
    ): Long =
        try {
            Math.subtractExact(left, right)
        } catch (failure: ArithmeticException) {
            invalid("Financial event amount overflowed", failure)
        }

    private fun invalid(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { cause?.let(it::initCause) }

    private companion object {
        const val KRW = "KRW"
        const val PAYMENT_REFUNDED = "PaymentRefundedV1"
        const val POINTS_ACCRUED = "PointsAccruedV1"
        const val POINTS_RESTORED = "PointsRestoredV1"
        const val SETTLEMENT_ITEM_CREATED = "SettlementItemCreatedV1"
        const val SETTLEMENT_BATCH_CONFIRMED = "SettlementBatchConfirmedV1"
        const val SETTLEMENT_ADJUSTMENT_CREATED = "SettlementAdjustmentCreatedV1"
        const val CUSTOMER_CANCELLATION_REFUND_SUCCEEDED = "CustomerCancellationRefundSucceededV1"
        const val CUSTOMER_CANCELLATION_REFUND_DELAYED = "CustomerCancellationRefundDelayedV1"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val ZERO_UUID: UUID = UUID(0, 0)
        val SETTLEMENT_ADJUSTMENT_REASONS = setOf("REFUND_SUCCEEDED", "DISPUTE_ACCEPTED")
    }
}
