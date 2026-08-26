package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.Instant

internal data class PersistedOrderLifecycle(
    val paidAt: Instant?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
) {
    fun hasOccurredEvent(): Boolean = listOf(paidAt, acceptedAt, preparingAt, readyAt, completedAt).any { it != null }
}

internal object OrderLifecycleProjection {
    fun validate(
        state: OrderState,
        lifecycle: PersistedOrderLifecycle,
    ) {
        requireChronology(lifecycle.paidAt, lifecycle.acceptedAt, "acceptedAt")
        requireChronology(lifecycle.acceptedAt, lifecycle.preparingAt, "preparingAt")
        requireChronology(lifecycle.preparingAt, lifecycle.readyAt, "readyAt")
        requireChronology(lifecycle.readyAt, lifecycle.completedAt, "completedAt")

        val valid =
            when (state) {
                OrderState.PENDING_PAYMENT,
                OrderState.EXPIRED,
                -> {
                    !lifecycle.hasOccurredEvent()
                }

                OrderState.PAID,
                OrderState.REJECTED,
                -> {
                    lifecycle.paidAt != null && lifecycle.acceptedAt == null && lifecycle.preparingAt == null &&
                        lifecycle.readyAt == null && lifecycle.completedAt == null
                }

                OrderState.ACCEPTED -> {
                    lifecycle.paidAt != null && lifecycle.acceptedAt != null && lifecycle.preparingAt == null &&
                        lifecycle.readyAt == null && lifecycle.completedAt == null
                }

                OrderState.PREPARING -> {
                    lifecycle.paidAt != null && lifecycle.acceptedAt != null && lifecycle.preparingAt != null &&
                        lifecycle.readyAt == null && lifecycle.completedAt == null
                }

                OrderState.READY -> {
                    lifecycle.paidAt != null && lifecycle.acceptedAt != null && lifecycle.preparingAt != null &&
                        lifecycle.readyAt != null && lifecycle.completedAt == null
                }

                OrderState.COMPLETED -> {
                    lifecycle.paidAt != null && lifecycle.acceptedAt != null && lifecycle.preparingAt != null &&
                        lifecycle.readyAt != null && lifecycle.completedAt != null
                }

                OrderState.CANCELLED -> {
                    lifecycle.completedAt == null && isPrefix(lifecycle)
                }
            }
        if (!valid) dependency("Order lifecycle projection contradicts its state")
    }

    private fun isPrefix(lifecycle: PersistedOrderLifecycle): Boolean =
        (lifecycle.acceptedAt == null || lifecycle.paidAt != null) &&
            (lifecycle.preparingAt == null || lifecycle.acceptedAt != null) &&
            (lifecycle.readyAt == null || lifecycle.preparingAt != null)

    private fun requireChronology(
        earlier: Instant?,
        later: Instant?,
        field: String,
    ) {
        if (later != null && (earlier == null || later.isBefore(earlier))) {
            dependency("Order lifecycle $field is inconsistent")
        }
    }

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}
