package io.github.kdh949.beanflow.inventory.internal

import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationOperations
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepState
import io.github.kdh949.beanflow.operations.api.RejectionCompensationStepType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class OrderRejectedStockListener(
    private val stockOperations: StockReservationOperations,
    private val compensationOperations: RejectionCompensationOperations,
) {
    @ApplicationModuleListener
    fun on(event: OrderRejectedV1) {
        val report =
            stockOperations.restoreConfirmedByRejection(
                event.orderId,
                event.rejectedAt,
                "event:${event.envelope.eventId}:stock",
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Confirmed stock reservations could not be restored",
            )
        }
        compensationOperations.recordStep(
            event.orderId,
            RejectionCompensationStepType.STOCK,
            RejectionCompensationStepState.SUCCEEDED,
            null,
            event.rejectedAt,
        )
    }
}
