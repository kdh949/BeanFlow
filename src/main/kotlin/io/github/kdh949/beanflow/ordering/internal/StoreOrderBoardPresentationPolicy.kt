package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.Instant

internal object StoreOrderBoardPresentationPolicy {
    fun present(
        state: OrderState,
        acceptanceWarningAt: Instant?,
        acceptanceDeadlineAt: Instant?,
        now: Instant,
    ): StoreOrderBoardPresentation =
        when (state) {
            OrderState.PAID -> {
                val warningAt = acceptanceWarningAt ?: dependency("Paid order has no acceptance warning boundary")
                val deadlineAt = acceptanceDeadlineAt ?: dependency("Paid order has no acceptance deadline")
                val phase =
                    when {
                        !now.isBefore(deadlineAt) -> StoreOrderAcceptancePhase.TIMEOUT_PENDING
                        !now.isBefore(warningAt) -> StoreOrderAcceptancePhase.WARNING
                        else -> StoreOrderAcceptancePhase.OPEN
                    }
                StoreOrderBoardPresentation(
                    lane = StoreOrderBoardLane.PENDING_ACCEPTANCE,
                    acceptancePhase = phase,
                    allowedActions =
                        if (phase == StoreOrderAcceptancePhase.TIMEOUT_PENDING) {
                            emptyList()
                        } else {
                            listOf(StoreOrderAction.ACCEPT, StoreOrderAction.REJECT)
                        },
                )
            }

            OrderState.ACCEPTED -> {
                active(StoreOrderBoardLane.ACCEPTED, StoreOrderAction.START_PREPARING)
            }

            OrderState.PREPARING -> {
                active(StoreOrderBoardLane.PREPARING, StoreOrderAction.MARK_READY)
            }

            OrderState.READY -> {
                active(StoreOrderBoardLane.READY, StoreOrderAction.COMPLETE)
            }

            else -> {
                StoreOrderBoardPresentation(null, null, emptyList())
            }
        }

    fun targetState(
        action: StoreOrderAction,
        expectedStatus: StoreOrderExpectedStatus,
    ): StoreOrderTargetState =
        when (action to expectedStatus) {
            StoreOrderAction.ACCEPT to StoreOrderExpectedStatus.PAID -> {
                StoreOrderTargetState.ACCEPTED
            }

            StoreOrderAction.REJECT to StoreOrderExpectedStatus.PAID -> {
                StoreOrderTargetState.REJECTED
            }

            StoreOrderAction.START_PREPARING to StoreOrderExpectedStatus.ACCEPTED -> {
                StoreOrderTargetState.PREPARING
            }

            StoreOrderAction.MARK_READY to StoreOrderExpectedStatus.PREPARING -> {
                StoreOrderTargetState.READY
            }

            StoreOrderAction.COMPLETE to StoreOrderExpectedStatus.READY -> {
                StoreOrderTargetState.COMPLETED
            }

            else -> {
                throw DomainFailure(
                    FailureCode.ORDER_ACTION_NOT_ALLOWED,
                    "The store order action is not allowed for the expected status",
                )
            }
        }

    private fun active(
        lane: StoreOrderBoardLane,
        action: StoreOrderAction,
    ) = StoreOrderBoardPresentation(lane, null, listOf(action))

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}
