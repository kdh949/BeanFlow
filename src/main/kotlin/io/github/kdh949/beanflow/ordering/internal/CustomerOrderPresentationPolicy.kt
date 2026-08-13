package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.Instant

internal enum class CustomerOrderStatusFilter {
    ACTIVE,
    PAST,
}

internal enum class CustomerOrderAllowedAction {
    CANCEL,
    REORDER,
    VIEW_REFUND,
}

internal data class CustomerOrderActionFacts(
    val state: OrderState,
    val reservationExpiresAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
    val cancellationCause: OrderCancellationCause?,
)

internal object CustomerOrderPresentationPolicy {
    private val activeStates =
        setOf(
            OrderState.PENDING_PAYMENT,
            OrderState.PAID,
            OrderState.ACCEPTED,
            OrderState.PREPARING,
            OrderState.READY,
        )
    private val pastStates =
        setOf(
            OrderState.COMPLETED,
            OrderState.REJECTED,
            OrderState.EXPIRED,
            OrderState.CANCELLED,
        )

    fun matches(
        state: OrderState,
        filter: CustomerOrderStatusFilter,
    ): Boolean =
        when (filter) {
            CustomerOrderStatusFilter.ACTIVE -> state in activeStates
            CustomerOrderStatusFilter.PAST -> state in pastStates
        }

    fun allowedActions(
        facts: CustomerOrderActionFacts,
        now: Instant,
    ): List<CustomerOrderAllowedAction> =
        buildList {
            if (canCancel(facts, now)) add(CustomerOrderAllowedAction.CANCEL)
            if (facts.state in pastStates) add(CustomerOrderAllowedAction.REORDER)
            if (
                facts.state == OrderState.CANCELLED &&
                facts.cancellationCause == OrderCancellationCause.CUSTOMER_REQUEST
            ) {
                add(CustomerOrderAllowedAction.VIEW_REFUND)
            }
        }

    fun itemSummary(menuNames: List<String>): String {
        val first = menuNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: dependency("Order has no displayable line")
        return if (menuNames.size == 1) first else "$first 외 ${menuNames.size - 1}건"
    }

    private fun canCancel(
        facts: CustomerOrderActionFacts,
        now: Instant,
    ): Boolean =
        when (facts.state) {
            OrderState.PENDING_PAYMENT ->
                now.isBefore(
                    facts.reservationExpiresAt ?: dependency("Pending-payment order has no reservation deadline"),
                )

            OrderState.PAID ->
                now.isBefore(
                    facts.acceptanceDeadlineAt ?: dependency("Paid order has no acceptance deadline"),
                )

            else -> false
        }

    private fun dependency(message: String): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}
