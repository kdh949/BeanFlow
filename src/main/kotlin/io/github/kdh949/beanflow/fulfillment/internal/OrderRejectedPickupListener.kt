package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedPickupListener(
    private val pickupOperations: PickupReservationOperations,
    private val compensationOperations: RejectionCompensationOperations,
) {
    @ApplicationModuleListener
    fun on(event: OrderRejectedV1) {
        val report =
            pickupOperations.releaseConfirmedByRejection(
                event.orderId,
                event.rejectedAt,
                "event:${event.envelope.eventId}:pickup",
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Confirmed pickup reservation could not be restored",
            )
        }
        compensationOperations.recordStep(
            event.orderId,
            RejectionCompensationStepType.PICKUP,
            RejectionCompensationStepState.SUCCEEDED,
            null,
            event.rejectedAt,
        )
    }
}
