package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.notification.api.GoodwillCompensationNotificationOperations
import io.github.kdh949.beanflow.notification.api.RequestGoodwillCompensationNotificationCommand
import io.github.kdh949.beanflow.ordering.api.GoodwillCompensationOrderOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationRequestState
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class SupportCompensationApplicationService(
    private val evaluationHandler: EvaluateSupportCompensationHandler,
    private val createHandler: CreateSupportCompensationHandler,
    private val executeHandler: ExecuteSupportCompensationHandler,
    private val notificationRetryHandler: RetrySupportCompensationNotificationHandler,
    private val queryHandler: QuerySupportCompensationHandler,
) {
    fun evaluate(command: EvaluateSupportCompensationCommand): SupportCompensationEvaluationResource = evaluationHandler.handle(command)

    fun create(command: CreateSupportCompensationCommand): SupportCompensationResource = createHandler.handle(command)

    fun execute(command: ExecuteSupportCompensationCommand): SupportCompensationResource = executeHandler.handle(command)

    fun retryNotification(command: RetrySupportCompensationNotificationCommand): SupportCompensationResource =
        notificationRetryHandler.handle(command)

    fun get(
        actorId: UUID,
        compensationRequestId: UUID,
    ): SupportCompensationResource = queryHandler.get(actorId, compensationRequestId)
}

@Service
internal class EvaluateSupportCompensationHandler(
    private val ordering: GoodwillCompensationOrderOperations,
    private val transactions: SupportCompensationTransactionService,
) {
    fun handle(command: EvaluateSupportCompensationCommand): SupportCompensationEvaluationResource =
        transactions.evaluate(command, command.orderId?.let(ordering::find))
}

@Service
internal class CreateSupportCompensationHandler(
    private val ordering: GoodwillCompensationOrderOperations,
    private val transactions: SupportCompensationTransactionService,
) {
    fun handle(command: CreateSupportCompensationCommand): SupportCompensationResource =
        transactions.create(command, command.orderId?.let(ordering::find))
}

@Service
internal class ExecuteSupportCompensationHandler(
    private val ordering: GoodwillCompensationOrderOperations,
    private val transactions: SupportCompensationTransactionService,
    private val notifications: SupportCompensationNotificationHandler,
) {
    fun handle(command: ExecuteSupportCompensationCommand): SupportCompensationResource {
        val request = transactions.executionOrderBinding(command.compensationRequestId)
        val order = request.orderId?.let(ordering::find)
        val issued = persistenceBoundary { transactions.execute(command, order) }
        return notifications.dispatch(issued, command.actorId, null)
    }

    private fun <T> persistenceBoundary(block: () -> T): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support compensation persistence is unavailable").also {
                it.initCause(failure)
            }
        }
}

@Service
internal class RetrySupportCompensationNotificationHandler(
    private val transactions: SupportCompensationTransactionService,
    private val notifications: SupportCompensationNotificationHandler,
) {
    fun handle(command: RetrySupportCompensationNotificationCommand): SupportCompensationResource {
        val resource = transactions.prepareNotificationRetry(command)
        return notifications.dispatch(resource, command.actorId, command)
    }
}

@Service
internal class QuerySupportCompensationHandler(
    private val transactions: SupportCompensationTransactionService,
    private val notifications: GoodwillCompensationNotificationOperations,
) {
    fun get(
        actorId: UUID,
        compensationRequestId: UUID,
    ): SupportCompensationResource {
        val resource = transactions.get(actorId, compensationRequestId)
        val notificationState =
            if (resource.state == SupportCompensationRequestState.NOTIFICATION_SKIPPED) {
                "NOTIFICATION_SKIPPED"
            } else {
                resource.notificationDeliveryId?.let(notifications::findGoodwill)?.state
            }
        return resource.copy(notificationState = notificationState)
    }
}

@Service
internal class SupportCompensationNotificationHandler(
    private val transactions: SupportCompensationTransactionService,
    private val notifications: GoodwillCompensationNotificationOperations,
    private val correlations: CorrelationIdSource,
    private val queries: QuerySupportCompensationHandler,
) {
    fun dispatch(
        resource: SupportCompensationResource,
        actorId: UUID,
        retry: RetrySupportCompensationNotificationCommand?,
    ): SupportCompensationResource {
        if (resource.state in
            setOf(
                SupportCompensationRequestState.NOTIFICATION_ACCEPTED,
                SupportCompensationRequestState.NOTIFICATION_SKIPPED,
            )
        ) {
            return queries.get(actorId, resource.compensationRequestId)
        }
        val accepted =
            try {
                notifications.requestGoodwill(
                    RequestGoodwillCompensationNotificationCommand(
                        resource.compensationRequestId,
                        resource.orderId,
                        transactions.customerId(resource.compensationRequestId),
                        resource.storeId,
                        resource.benefitType.name,
                        resource.amountKrw,
                        requireNotNull(resource.benefitIssuedAt) { "Issued compensation is missing terminal time" },
                        correlations.currentOrCreate(),
                    ),
                )
            } catch (failure: RuntimeException) {
                return transactions.recordNotificationFailure(resource.compensationRequestId, retry, actorId, failure)
            }
        if (accepted.state == "NOTIFICATION_SKIPPED") {
            transactions.skipNotification(resource.compensationRequestId, retry, actorId)
        } else {
            transactions.completeNotification(
                resource.compensationRequestId,
                requireNotNull(accepted.deliveryId) { "Accepted notification must bind a delivery" },
                retry,
                actorId,
            )
        }
        return queries.get(actorId, resource.compensationRequestId)
    }
}
