package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.OpenRejectionCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.RejectionCompensationCaseView
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.ordering.api.EventEnvelope
import io.github.kdh949.beanflow.ordering.api.OrderRejectedV1
import io.github.kdh949.beanflow.ordering.api.OrderRejectionActorType
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

internal data class RejectionActor(
    val actorId: String,
    val actorType: OrderRejectionActorType,
)

@Component
internal class OrderRejectionCoordinator(
    private val policyOperations: ExpiredBenefitRestorationPolicyOperations,
    private val compensationOperations: RejectionCompensationOperations,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierSource: IdentifierSource,
) {
    fun reject(
        order: OrderEntity,
        actor: RejectionActor,
        reason: String,
        now: Instant,
        correlationId: String,
        causationId: String,
    ): RejectionCompensationCaseView {
        order.reject(now, reason)
        val policy = policyOperations.current()
        val eventId = identifierSource.next()
        val sourceReference = "order:${order.id}:rejection:${order.version + 1}"
        val recovery =
            compensationOperations.open(
                OpenRejectionCompensationCaseCommand(
                    caseId = identifierSource.next(),
                    eventId = eventId,
                    orderId = order.id,
                    customerId = order.customerId,
                    storeId = order.storeId,
                    sourceReference = sourceReference,
                    policy = policy,
                    paymentRequired = order.payableKrw > 0,
                    couponRequired = order.couponDiscountKrw > 0,
                    pointsRequired = order.pointsAppliedKrw > 0,
                    correlationId = correlationId,
                    now = now,
                ),
            )
        eventPublisher.publishEvent(
            OrderRejectedV1(
                envelope =
                    EventEnvelope(
                        eventId = eventId,
                        eventType = "OrderRejectedV1",
                        aggregateId = order.id,
                        aggregateVersion = order.version + 1,
                        occurredAt = now,
                        payloadVersion = 1,
                        correlationId = correlationId,
                        causationId = causationId,
                    ),
                orderId = order.id,
                customerId = order.customerId,
                storeId = order.storeId,
                actorId = actor.actorId,
                actorType = actor.actorType,
                reason = reason.trim(),
                rejectedAt = now,
                policyVersion = policy.policyVersion,
                policyMode = policy.mode.name,
                policyValidityDays = policy.compensationValidityDays,
            ),
        )
        return recovery
    }
}
