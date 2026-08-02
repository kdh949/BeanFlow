package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectionActorType
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
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
