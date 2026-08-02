package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import org.springframework.stereotype.Component

internal data class CompensationPublicationTarget(
    val eventType: String,
    val listenerId: String,
    val stepType: OrderCompensationStepType,
)

@Component
internal class CompensationPublicationTargetRegistry {
    private val targets = requireUnique(DEFAULT_TARGETS)

    fun find(
        event: Any,
        listenerId: String,
    ): OrderCompensationStepType? = targets[event.javaClass.name to listenerId]

    internal fun requireUnique(targets: List<CompensationPublicationTarget>): Map<Pair<String, String>, OrderCompensationStepType> {
        val grouped = targets.groupBy { it.eventType to it.listenerId }
        check(grouped.values.none { it.size > 1 }) { "Duplicate compensation publication target" }
        return grouped.mapValues { (_, values) -> values.single().stepType }
    }

    private companion object {
        val REJECTED_EVENT = OrderRejectedV1::class.java.name
        val CANCELLED_EVENT = OrderCancelledV1::class.java.name
        val DEFAULT_TARGETS =
            listOf(
                target(REJECTED_EVENT, "payment", OrderCompensationStepType.PAYMENT),
                target(REJECTED_EVENT, "pickup", OrderCompensationStepType.PICKUP),
                target(REJECTED_EVENT, "stock", OrderCompensationStepType.STOCK),
                target(REJECTED_EVENT, "coupon", OrderCompensationStepType.COUPON),
                target(REJECTED_EVENT, "points", OrderCompensationStepType.POINTS),
                target(REJECTED_EVENT, "customer-notification", OrderCompensationStepType.CUSTOMER_NOTIFICATION),
                target(CANCELLED_EVENT, "pickup", OrderCompensationStepType.PICKUP),
                target(CANCELLED_EVENT, "stock", OrderCompensationStepType.STOCK),
                target(CANCELLED_EVENT, "coupon", OrderCompensationStepType.COUPON),
                target(CANCELLED_EVENT, "points", OrderCompensationStepType.POINTS),
            )

        fun target(
            eventType: String,
            stepName: String,
            stepType: OrderCompensationStepType,
        ) = CompensationPublicationTarget(
            eventType,
            "beanflow.order-compensation.${if (eventType == REJECTED_EVENT) "order-rejected" else "order-cancelled"}.$stepName.v1",
            stepType,
        )
    }
}
