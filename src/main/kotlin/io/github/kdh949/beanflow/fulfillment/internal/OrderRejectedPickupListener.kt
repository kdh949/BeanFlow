package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReleasePickupAfterTerminationCommand
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedPickupListener(
    private val pickupOperations: PickupReservationOperations,
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.pickup.v1")
    fun on(event: OrderRejectedV1) {
        release(
            event.orderId,
            event.rejectedAt,
            "event:${event.envelope.eventId}:pickup",
            OrderTerminationTrigger.STORE_REJECTION,
        )
    }

    @ApplicationModuleListener(id = "beanflow.order-compensation.order-cancelled.pickup.v1")
    fun on(event: OrderCancelledV1) {
        release(
            event.orderId,
            event.cancelledAt,
            "order:${event.orderId}:customer-cancellation:${event.envelope.aggregateVersion}:pickup",
            OrderTerminationTrigger.CUSTOMER_CANCELLATION,
        )
    }

    private fun release(
        orderId: java.util.UUID,
        terminatedAt: java.time.Instant,
        sourceReference: String,
        trigger: OrderTerminationTrigger,
    ) {
        val report =
            pickupOperations.releaseConfirmedAfterTermination(
                ReleasePickupAfterTerminationCommand(
                    orderId,
                    terminatedAt,
                    sourceReference,
                    trigger,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Confirmed pickup reservation could not be restored",
            )
        }
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.PICKUP,
            OrderCompensationStepState.SUCCEEDED,
            null,
            terminatedAt,
        )
    }
}
