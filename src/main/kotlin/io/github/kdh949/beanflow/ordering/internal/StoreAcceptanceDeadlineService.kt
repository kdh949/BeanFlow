package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.eventing.api.StoreAcceptanceWarningRequestedV1
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

internal enum class StoreAcceptanceDeadlineOutcome {
    APPLIED,
    NOT_ELIGIBLE,
}

@Service
internal class StoreAcceptanceDeadlineService(
    private val orderRepository: OrderJpaRepository,
    private val rejectionCoordinator: OrderRejectionCoordinator,
    private val auditRecordOperations: AuditRecordOperations,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierSource: IdentifierSource,
    private val correlationIdSource: CorrelationIdSource,
) {
    @Transactional
    fun requestWarning(
        orderId: UUID,
        now: Instant,
    ): StoreAcceptanceDeadlineOutcome {
        val order = locked(orderId)
        if (!order.markAcceptanceWarningRequested(now)) {
            return StoreAcceptanceDeadlineOutcome.NOT_ELIGIBLE
        }
        val eventId = identifierSource.next()
        val correlationId = correlationIdSource.currentOrCreate()
        val causationId = "order:$orderId:acceptance-warning"
        eventPublisher.publishEvent(
            StoreAcceptanceWarningRequestedV1(
                envelope =
                    EventEnvelope(
                        eventId,
                        "StoreAcceptanceWarningRequestedV1",
                        order.id,
                        order.version + 1,
                        now,
                        1,
                        correlationId,
                        causationId,
                    ),
                orderId = order.id,
                storeId = order.storeId,
                acceptanceDeadlineAt = requireNotNull(order.acceptanceDeadlineAt),
            ),
        )
        appendAudit(
            order,
            "STORE_ACCEPTANCE_WARNING_REQUESTED",
            "PAID",
            "PAID",
            now,
            correlationId,
            causationId,
        )
        return StoreAcceptanceDeadlineOutcome.APPLIED
    }

    @Transactional
    fun rejectTimedOut(
        orderId: UUID,
        now: Instant,
    ): StoreAcceptanceDeadlineOutcome {
        val order = locked(orderId)
        if (order.state != OrderState.PAID) {
            return StoreAcceptanceDeadlineOutcome.NOT_ELIGIBLE
        }
        val deadline =
            order.acceptanceDeadlineAt
                ?: throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Paid order has no acceptance deadline",
                )
        if (now.isBefore(deadline)) {
            return StoreAcceptanceDeadlineOutcome.NOT_ELIGIBLE
        }
        val correlationId = correlationIdSource.currentOrCreate()
        val causationId = "order:$orderId:acceptance-timeout"
        rejectionCoordinator.reject(
            order = order,
            actor = RejectionActor("SYSTEM", OrderRejectionActorType.SYSTEM_TIMEOUT),
            reason = "STORE_ACCEPTANCE_TIMEOUT",
            now = now,
            correlationId = correlationId,
            causationId = causationId,
        )
        appendAudit(
            order,
            "STORE_ORDER_REJECTED_BY_TIMEOUT",
            "PAID",
            "REJECTED",
            now,
            correlationId,
            causationId,
        )
        return StoreAcceptanceDeadlineOutcome.APPLIED
    }

    private fun appendAudit(
        order: OrderEntity,
        action: String,
        before: String,
        after: String,
        now: Instant,
        correlationId: String,
        sourceReference: String,
    ) {
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = "SYSTEM",
                    actorType = AuditActorType.SYSTEM,
                    action = action,
                    targetType = "ORDER",
                    targetId = order.id,
                    occurredAt = now,
                    reason = action,
                    beforeSummary = mapOf("state" to before),
                    afterSummary = mapOf("state" to after),
                    correlationId = correlationId,
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun locked(orderId: UUID): OrderEntity =
        orderRepository.findLockedById(orderId)
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
}
