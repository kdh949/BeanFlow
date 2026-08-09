package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState

internal object FastReorderSourcePolicy {
    private val allowed =
        setOf(
            OrderState.COMPLETED,
            OrderState.CANCELLED,
            OrderState.REJECTED,
            OrderState.EXPIRED,
        )

    fun isAllowed(state: OrderState): Boolean = state in allowed
}
