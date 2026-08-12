package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReschedulePickupCommand
import io.github.kdh949.beanflow.fulfillment.api.ReschedulePickupResult
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.ordering.api.OrderingSupportPickupRescheduleOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerReport
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerResult
import io.github.kdh949.beanflow.ordering.api.SupportPickupRescheduleCommand
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
internal class OrderingSupportPickupRescheduleService(
    private val orders: OrderJpaRepository,
    private val histories: OrderingSupportOrderChangeHistoryJpaRepository,
    private val pickupReservations: PickupReservationOperations,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val clock: Clock,
) : OrderingSupportPickupRescheduleOperations {
    @Transactional
    override fun reschedule(command: SupportPickupRescheduleCommand): SupportOrderChangeOwnerReport {
        validate(command)
        histories.findBySourceReference(command.sourceReference)?.let { return replay(it, command) }
        val order = orders.findLockedById(command.orderId) ?: notFound()
        if (order.state in POST_ACCEPTANCE_RESOLUTION_STATES) return resolutionRequired(order)
        if (order.version != command.expectedOrderVersion) stale()
        if (order.state == OrderState.ACCEPTED && command.acceptedStoreAuthorizationId == null) denied()

        val previousState = order.state.name
        val previousSlotId = order.pickupSlotId
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val pickup =
            pickupReservations.reschedule(
                ReschedulePickupCommand(order.id, order.storeId, command.newPickupSlotId, command.sourceReference),
            )
        if (pickup.result != ReschedulePickupResult.APPLIED) dependency()
        order.reschedulePickupBySupport(now, command.newPickupSlotId)
        val history =
            OrderingSupportOrderChangeHistoryEntity(
                identifiers.next(),
                order.id,
                command.supportRequestId,
                command.supportExecutionId,
                OrderingSupportOrderChangeAction.PICKUP_RESCHEDULE,
                previousState,
                order.state.name,
                previousSlotId,
                order.pickupSlotId,
                order.version + 1,
                null,
                command.sourceReference,
                now,
            )
        histories.save(history)
        appendAudit(command, history)
        return history.report(SupportOrderChangeOwnerResult.APPLIED)
    }

    private fun validate(command: SupportPickupRescheduleCommand) {
        if (command.expectedOrderVersion < 0 || command.sourceReference.trim() != command.sourceReference ||
            command.sourceReference.length !in 1..240 || command.sourceReference.any(Char::isISOControl)
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Support pickup reschedule command is invalid")
        }
    }

    private fun replay(
        history: OrderingSupportOrderChangeHistoryEntity,
        command: SupportPickupRescheduleCommand,
    ): SupportOrderChangeOwnerReport {
        if (history.action != OrderingSupportOrderChangeAction.PICKUP_RESCHEDULE || history.orderId != command.orderId ||
            history.supportRequestId != command.supportRequestId || history.supportExecutionId != command.supportExecutionId ||
            history.currentPickupSlotId != command.newPickupSlotId
        ) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Support order change source was reused")
        }
        return history.report(SupportOrderChangeOwnerResult.ALREADY_APPLIED)
    }

    private fun resolutionRequired(order: OrderEntity) =
        SupportOrderChangeOwnerReport(
            SupportOrderChangeOwnerResult.RESOLUTION_REQUIRED,
            order.id,
            order.state.name,
            order.state.name,
            order.pickupSlotId,
            order.pickupSlotId,
            order.version,
        )

    private fun OrderingSupportOrderChangeHistoryEntity.report(result: SupportOrderChangeOwnerResult) =
        SupportOrderChangeOwnerReport(
            result,
            orderId,
            previousState,
            currentState,
            previousPickupSlotId,
            currentPickupSlotId,
            orderVersion,
            paymentRecoveryState,
        )

    private fun appendAudit(
        command: SupportPickupRescheduleCommand,
        history: OrderingSupportOrderChangeHistoryEntity,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.ORDER_AND_FULFILLMENT,
                    action = "ORDER_SUPPORT_PICKUP_RESCHEDULED",
                    targetType = "ORDER",
                    targetId = command.orderId,
                    occurredAt = history.occurredAt,
                    reason = "SUPPORT_CASE_RESOLUTION",
                    beforeSummary = mapOf("state" to history.previousState, "pickupSlotChanged" to "false"),
                    afterSummary = mapOf("state" to history.currentState, "pickupSlotChanged" to "true"),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "${command.sourceReference}:order-audit",
                ),
            ),
        )
    }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")

    private fun stale(): Nothing = throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "Order version changed before execution")

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Accepted order change requires store authorization")

    private fun dependency(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Pickup reschedule replay is inconsistent")

    private companion object {
        val POST_ACCEPTANCE_RESOLUTION_STATES = setOf(OrderState.PREPARING, OrderState.READY, OrderState.COMPLETED)
    }
}
