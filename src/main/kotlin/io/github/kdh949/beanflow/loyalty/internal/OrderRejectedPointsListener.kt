package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.RestorePointsAfterTerminationCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class OrderRejectedPointsListener(
    private val pointOperations: PointReservationOperations,
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.points.v1")
    fun on(event: OrderRejectedV1) {
        if (!event.pointsRequired) return
        restore(
            event.orderId,
            event.rejectedAt,
            "event:${event.envelope.eventId}:points",
            OrderTerminationTrigger.STORE_REJECTION,
            event.pointsPolicy,
        )
    }

    @ApplicationModuleListener(id = "beanflow.order-compensation.order-cancelled.points.v1")
    fun on(event: OrderCancelledV1) {
        if (!event.pointsRequired) return
        restore(
            event.orderId,
            event.cancelledAt,
            "order:${event.orderId}:customer-cancellation:${event.envelope.aggregateVersion}:points",
            OrderTerminationTrigger.CUSTOMER_CANCELLATION,
            event.pointsPolicy,
        )
    }

    private fun restore(
        orderId: UUID,
        terminatedAt: Instant,
        sourceReference: String,
        trigger: OrderTerminationTrigger,
        policy: BenefitRestorationPolicySnapshotV1,
    ) {
        val report =
            pointOperations.restoreUsedAfterTermination(
                RestorePointsAfterTerminationCommand(
                    orderId = orderId,
                    terminatedAt = terminatedAt,
                    sourceReference = sourceReference,
                    trigger = trigger,
                    policyVersionId = policy.policyVersionId,
                    mode = ExpiredPointRestorationMode.valueOf(policy.mode),
                    compensationValidityDays = policy.compensationValidityDays,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Used points could not be restored",
            )
        }
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.POINTS,
            OrderCompensationStepState.SUCCEEDED,
            null,
            terminatedAt,
        )
    }
}
