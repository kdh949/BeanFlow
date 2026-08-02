package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.RestorePointsByRejectionCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedPointsListener(
    private val pointOperations: PointReservationOperations,
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.points.v1")
    fun on(event: OrderRejectedV1) {
        if (!event.pointsRequired) return
        val report =
            pointOperations.restoreUsedByRejection(
                RestorePointsByRejectionCommand(
                    orderId = event.orderId,
                    rejectedAt = event.rejectedAt,
                    sourceReference = "event:${event.envelope.eventId}:points",
                    mode = ExpiredPointRestorationMode.valueOf(event.pointsPolicy.mode),
                    compensationValidityDays = event.pointsPolicy.compensationValidityDays,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Used points could not be restored",
            )
        }
        compensationOperations.recordStep(
            event.orderId,
            OrderCompensationStepType.POINTS,
            OrderCompensationStepState.SUCCEEDED,
            null,
            event.rejectedAt,
        )
    }
}
