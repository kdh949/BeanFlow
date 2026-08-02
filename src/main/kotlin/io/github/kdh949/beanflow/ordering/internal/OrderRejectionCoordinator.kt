package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.OpenOrderCompensationCaseCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationCaseView
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationTrigger
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

internal data class RejectionActor(
    val actorId: String,
    val actorType: OrderRejectionActorType,
)

@Component
internal class OrderRejectionCoordinator(
    private val policyOperations: ExpiredBenefitRestorationPolicyOperations,
    private val compensationOperations: OrderCompensationOperations,
    private val eventPublisher: ApplicationEventPublisher,
    private val identifierSource: IdentifierSource,
    private val meterRegistry: MeterRegistry,
) {
    fun reject(
        order: OrderEntity,
        actor: RejectionActor,
        reason: String,
        now: Instant,
        correlationId: String,
        causationId: String,
    ): OrderCompensationCaseView {
        order.reject(now, reason)
        val couponPolicy =
            policyOperations.current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.COUPON)
        val pointsPolicy =
            policyOperations.current(ExpiredBenefitRestorationTrigger.STORE_REJECTION, ExpiredBenefitType.POINTS)
        val eventId = identifierSource.next()
        val terminalOrderVersion = order.version + 1
        val sourceReference = "order:${order.id}:rejection:$terminalOrderVersion"
        val recovery =
            compensationOperations.open(
                OpenOrderCompensationCaseCommand(
                    caseId = identifierSource.next(),
                    eventId = eventId,
                    orderId = order.id,
                    terminalOrderVersion = terminalOrderVersion,
                    customerId = order.customerId,
                    storeId = order.storeId,
                    trigger = OrderCompensationTrigger.STORE_REJECTION,
                    sourceReference = sourceReference,
                    couponPolicy = couponPolicy,
                    pointsPolicy = pointsPolicy,
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
                        aggregateVersion = terminalOrderVersion,
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
                couponPolicy = couponPolicy.toEventSnapshot(),
                pointsPolicy = pointsPolicy.toEventSnapshot(),
                paymentRequired = order.payableKrw > 0,
                couponRequired = order.couponDiscountKrw > 0,
                pointsRequired = order.pointsAppliedKrw > 0,
            ),
        )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    meterRegistry
                        .counter(
                            "beanflow.order.termination.event.count",
                            "event_type",
                            "order_rejected_v1",
                        ).increment()
                }
            },
        )
        return recovery
    }

    private fun io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot.toEventSnapshot() =
        BenefitRestorationPolicySnapshotV1(
            policyVersionId = policyVersion,
            mode = mode.name,
            compensationValidityDays = compensationValidityDays,
        )
}
