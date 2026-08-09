package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class PaymentMethodAuditWriter(
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
) {
    fun customer(
        actorId: UUID,
        action: String,
        targetType: String,
        targetId: UUID,
        occurredAt: Instant,
        beforeState: String?,
        afterState: String,
        sourceReference: String,
        correlationId: String = correlationIds.currentOrCreate(),
    ) {
        append(
            actorId = actorId.toString(),
            actorType = AuditActorType.CUSTOMER,
            action = action,
            targetType = targetType,
            targetId = targetId,
            occurredAt = occurredAt,
            beforeState = beforeState,
            afterState = afterState,
            sourceReference = sourceReference,
            correlationId = correlationId,
        )
    }

    fun system(
        action: String,
        targetType: String,
        targetId: UUID,
        occurredAt: Instant,
        beforeState: String?,
        afterState: String,
        sourceReference: String,
    ) {
        append(
            actorId = "payment-method-lifecycle",
            actorType = AuditActorType.SYSTEM,
            action = action,
            targetType = targetType,
            targetId = targetId,
            occurredAt = occurredAt,
            beforeState = beforeState,
            afterState = afterState,
            sourceReference = sourceReference,
            correlationId = correlationIds.currentOrCreate(),
        )
    }

    private fun append(
        actorId: String,
        actorType: AuditActorType,
        action: String,
        targetType: String,
        targetId: UUID,
        occurredAt: Instant,
        beforeState: String?,
        afterState: String,
        sourceReference: String,
        correlationId: String,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId,
                    actorType = actorType,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    occurredAt = occurredAt,
                    reason = action,
                    beforeSummary = beforeState?.let { mapOf("state" to it) } ?: emptyMap(),
                    afterSummary = mapOf("state" to afterState),
                    correlationId = correlationId,
                    sourceReference = sourceReference,
                ),
            ),
        )
    }
}
