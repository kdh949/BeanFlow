package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderingSupportOrderCancellationOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderCancellationCommand
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerReport
import io.github.kdh949.beanflow.ordering.api.SupportOrderChangeOwnerResult
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
internal class OrderingSupportOrderCancellationService(
    private val transaction: CustomerCancellationTransaction,
    private val histories: OrderingSupportOrderChangeHistoryJpaRepository,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : OrderingSupportOrderCancellationOperations {
    @Transactional
    override fun cancel(command: SupportOrderCancellationCommand): SupportOrderChangeOwnerReport {
        validate(command)
        histories.findBySourceReference(command.sourceReference)?.let { return replay(it, command) }
        return when (val outcome = transaction.executeSupport(command)) {
            is SupportCancellationTransactionOutcome.Applied -> {
                persist(command, outcome)
            }

            is SupportCancellationTransactionOutcome.ResolutionRequired -> {
                SupportOrderChangeOwnerReport(
                    SupportOrderChangeOwnerResult.RESOLUTION_REQUIRED,
                    command.orderId,
                    outcome.currentState,
                    outcome.currentState,
                    outcome.pickupSlotId,
                    outcome.pickupSlotId,
                    outcome.orderVersion,
                )
            }
        }
    }

    private fun persist(
        command: SupportOrderCancellationCommand,
        outcome: SupportCancellationTransactionOutcome.Applied,
    ): SupportOrderChangeOwnerReport {
        val response = objectMapper.readValue(outcome.response.body, CustomerCancellationResponse::class.java)
        val history =
            OrderingSupportOrderChangeHistoryEntity(
                identifiers.next(),
                command.orderId,
                command.supportRequestId,
                command.supportExecutionId,
                OrderingSupportOrderChangeAction.ORDER_CANCELLATION,
                outcome.previousState,
                response.orderState,
                outcome.previousPickupSlotId,
                outcome.previousPickupSlotId,
                outcome.orderVersion,
                response.paymentRecovery.state,
                command.sourceReference,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        histories.save(history)
        return history.report(SupportOrderChangeOwnerResult.APPLIED)
    }

    private fun validate(command: SupportOrderCancellationCommand) {
        if (command.expectedOrderVersion < 0 || command.sourceReference.trim() != command.sourceReference ||
            command.sourceReference.length !in 1..240 || command.sourceReference.any(Char::isISOControl)
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Support order cancellation command is invalid")
        }
    }

    private fun replay(
        history: OrderingSupportOrderChangeHistoryEntity,
        command: SupportOrderCancellationCommand,
    ): SupportOrderChangeOwnerReport {
        if (history.action != OrderingSupportOrderChangeAction.ORDER_CANCELLATION || history.orderId != command.orderId ||
            history.supportRequestId != command.supportRequestId || history.supportExecutionId != command.supportExecutionId
        ) {
            throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Support order change source was reused")
        }
        return history.report(SupportOrderChangeOwnerResult.ALREADY_APPLIED)
    }

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
}
