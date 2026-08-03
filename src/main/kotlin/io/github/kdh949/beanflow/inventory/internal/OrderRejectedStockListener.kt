package io.github.kdh949.beanflow.inventory.internal

import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.inventory.api.RestoreStockAfterTerminationCommand
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
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
internal class OrderRejectedStockListener(
    private val stockOperations: StockReservationOperations,
    private val compensationOperations: OrderCompensationOperations,
) {
    @ApplicationModuleListener(id = "beanflow.order-compensation.order-rejected.stock.v1")
    fun on(event: OrderRejectedV1) {
        restore(
            event.orderId,
            event.rejectedAt,
            "event:${event.envelope.eventId}:stock",
            OrderTerminationTrigger.STORE_REJECTION,
        )
    }

    @ApplicationModuleListener(id = "beanflow.order-compensation.order-cancelled.stock.v1")
    fun on(event: OrderCancelledV1) {
        restore(
            event.orderId,
            event.cancelledAt,
            "order:${event.orderId}:customer-cancellation:${event.envelope.aggregateVersion}:stock",
            OrderTerminationTrigger.CUSTOMER_CANCELLATION,
        )
    }

    private fun restore(
        orderId: java.util.UUID,
        terminatedAt: java.time.Instant,
        sourceReference: String,
        trigger: OrderTerminationTrigger,
    ) {
        val report =
            stockOperations.restoreConfirmedAfterTermination(
                RestoreStockAfterTerminationCommand(
                    orderId,
                    terminatedAt,
                    sourceReference,
                    trigger,
                ),
            )
        if (report.result == ReservationTransitionResult.NOT_ELIGIBLE) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Confirmed stock reservations could not be restored",
            )
        }
        compensationOperations.recordStep(
            orderId,
            OrderCompensationStepType.STOCK,
            OrderCompensationStepState.SUCCEEDED,
            null,
            terminatedAt,
        )
    }
}
