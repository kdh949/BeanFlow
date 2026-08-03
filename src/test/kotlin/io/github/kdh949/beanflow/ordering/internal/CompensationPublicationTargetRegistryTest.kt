package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.fulfillment.internal.OrderRejectedPickupListener
import io.github.kdh949.beanflow.inventory.internal.OrderRejectedStockListener
import io.github.kdh949.beanflow.loyalty.internal.OrderRejectedPointsListener
import io.github.kdh949.beanflow.notification.internal.OrderRejectedNotificationListener
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.payment.internal.OrderRejectedRefundListener
import io.github.kdh949.beanflow.promotion.internal.OrderRejectedCouponListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.ApplicationModuleListener
import java.time.Instant
import java.util.UUID

internal class CompensationPublicationTargetRegistryTest {
    private val registry = CompensationPublicationTargetRegistry()

    @Test
    fun `registry maps the exact ten versioned listener targets`() {
        val rejected = rejectedEvent()
        val cancelled = cancelledEvent()
        val rejectedSteps =
            mapOf(
                "payment" to OrderCompensationStepType.PAYMENT,
                "pickup" to OrderCompensationStepType.PICKUP,
                "stock" to OrderCompensationStepType.STOCK,
                "coupon" to OrderCompensationStepType.COUPON,
                "points" to OrderCompensationStepType.POINTS,
                "customer-notification" to OrderCompensationStepType.CUSTOMER_NOTIFICATION,
            )
        val cancelledSteps =
            mapOf(
                "pickup" to OrderCompensationStepType.PICKUP,
                "stock" to OrderCompensationStepType.STOCK,
                "coupon" to OrderCompensationStepType.COUPON,
                "points" to OrderCompensationStepType.POINTS,
            )

        rejectedSteps.forEach { (name, step) ->
            assertThat(registry.find(rejected, "beanflow.order-compensation.order-rejected.$name.v1"))
                .isEqualTo(step)
        }
        cancelledSteps.forEach { (name, step) ->
            assertThat(registry.find(cancelled, "beanflow.order-compensation.order-cancelled.$name.v1"))
                .isEqualTo(step)
        }
        assertThat(registry.find(rejected, "beanflow.order-compensation.order-rejected.unknown.v1")).isNull()
    }

    @Test
    fun `duplicate registry target fails closed`() {
        val duplicate =
            CompensationPublicationTarget(
                eventType = OrderRejectedV1::class.java.name,
                listenerId = "beanflow.order-compensation.order-rejected.pickup.v1",
                stepType = OrderCompensationStepType.PICKUP,
            )

        assertThatIllegalStateException()
            .isThrownBy { registry.requireUnique(listOf(duplicate, duplicate.copy())) }
            .withMessage("Duplicate compensation publication target")
    }

    @Test
    fun `listener annotations expose exactly the registry target identifiers`() {
        val annotatedTargets =
            listOf(
                OrderRejectedRefundListener::class.java,
                OrderRejectedPickupListener::class.java,
                OrderRejectedStockListener::class.java,
                OrderRejectedCouponListener::class.java,
                OrderRejectedPointsListener::class.java,
                OrderRejectedNotificationListener::class.java,
            ).flatMap { listener ->
                listener.declaredMethods.mapNotNull { method ->
                    val annotation = method.getAnnotation(ApplicationModuleListener::class.java) ?: return@mapNotNull null
                    if (!annotation.id.startsWith("beanflow.order-compensation.")) return@mapNotNull null
                    method.parameterTypes.single().name to annotation.id
                }
            }.toSet()

        val expected =
            setOf(
                OrderRejectedV1::class.java.name to "beanflow.order-compensation.order-rejected.payment.v1",
                OrderRejectedV1::class.java.name to "beanflow.order-compensation.order-rejected.pickup.v1",
                OrderRejectedV1::class.java.name to "beanflow.order-compensation.order-rejected.stock.v1",
                OrderRejectedV1::class.java.name to "beanflow.order-compensation.order-rejected.coupon.v1",
                OrderRejectedV1::class.java.name to "beanflow.order-compensation.order-rejected.points.v1",
                OrderRejectedV1::class.java.name to
                    "beanflow.order-compensation.order-rejected.customer-notification.v1",
                OrderCancelledV1::class.java.name to "beanflow.order-compensation.order-cancelled.pickup.v1",
                OrderCancelledV1::class.java.name to "beanflow.order-compensation.order-cancelled.stock.v1",
                OrderCancelledV1::class.java.name to "beanflow.order-compensation.order-cancelled.coupon.v1",
                OrderCancelledV1::class.java.name to "beanflow.order-compensation.order-cancelled.points.v1",
            )

        assertThat(annotatedTargets).isEqualTo(expected)
        annotatedTargets.forEach { (eventType, listenerId) ->
            val event = if (eventType == OrderRejectedV1::class.java.name) rejectedEvent() else cancelledEvent()
            assertThat(registry.find(event, listenerId)).isNotNull()
        }
    }

    private fun rejectedEvent() =
        OrderRejectedV1(
            envelope("OrderRejectedV1"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            OrderRejectionActorType.STORE_STAFF,
            "OUT_OF_STOCK",
            NOW,
            policy(),
            policy(),
            true,
            true,
            true,
        )

    private fun cancelledEvent() =
        OrderCancelledV1(
            envelope("OrderCancelledV1"),
            UUID.randomUUID(),
            NOW,
            true,
            true,
            policy(),
            policy(),
        )

    private fun envelope(eventType: String) =
        EventEnvelope(UUID.randomUUID(), eventType, UUID.randomUUID(), 4, NOW, 1, "correlation", "causation")

    private fun policy() = BenefitRestorationPolicySnapshotV1(1, "PRESERVE_ORIGINAL_EXPIRY", 30)

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T10:00:00Z")
    }
}
