package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.RestorePointsAfterTerminationCommand
import io.github.kdh949.beanflow.loyalty.internal.OrderRejectedPointsListener
import io.github.kdh949.beanflow.operations.api.OrderCompensationOperations
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepState
import io.github.kdh949.beanflow.operations.api.OrderCompensationStepType
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.RestoreCouponAfterTerminationCommand
import io.github.kdh949.beanflow.promotion.internal.OrderRejectedCouponListener
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

internal class OrderTerminationBenefitCompensationListenerTest {
    @Test
    fun `customer cancellation benefit listeners use terminal source and complete only their step`() {
        val couponOperations = mock<CouponReservationOperations>()
        val pointOperations = mock<PointReservationOperations>()
        val compensationOperations = mock<OrderCompensationOperations>()
        var capturedCoupon: RestoreCouponAfterTerminationCommand? = null
        var capturedPoints: RestorePointsAfterTerminationCommand? = null
        `when`(couponOperations.restoreUsedAfterTermination(anyValue()))
            .thenAnswer {
                capturedCoupon = it.getArgument(0)
                ReservationTransitionReport(ReservationTransitionResult.APPLIED, listOf(UUID.randomUUID()))
            }
        `when`(pointOperations.restoreUsedAfterTermination(anyValue()))
            .thenAnswer {
                capturedPoints = it.getArgument(0)
                ReservationTransitionReport(ReservationTransitionResult.APPLIED, listOf(UUID.randomUUID()))
            }
        val event = cancellationEvent()

        OrderRejectedCouponListener(couponOperations, compensationOperations).on(event)
        OrderRejectedPointsListener(pointOperations, compensationOperations).on(event)

        val couponCommand = requireNotNull(capturedCoupon)
        assertThat(couponCommand.sourceReference)
            .isEqualTo("order:${event.orderId}:customer-cancellation:7:coupon")
        assertThat(couponCommand.trigger).isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION)
        assertThat(couponCommand.policyVersionId).isEqualTo(41)
        assertThat(couponCommand.mode).isEqualTo(ExpiredCouponRestorationMode.PRESERVE_ORIGINAL_EXPIRY)

        val pointCommand = requireNotNull(capturedPoints)
        assertThat(pointCommand.sourceReference)
            .isEqualTo("order:${event.orderId}:customer-cancellation:7:points")
        assertThat(pointCommand.trigger).isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION)
        assertThat(pointCommand.policyVersionId).isEqualTo(42)
        assertThat(pointCommand.mode).isEqualTo(ExpiredPointRestorationMode.PRESERVE_ORIGINAL_EXPIRY)
        verify(compensationOperations).recordStep(
            event.orderId,
            OrderCompensationStepType.COUPON,
            OrderCompensationStepState.SUCCEEDED,
            null,
            event.cancelledAt,
        )
        verify(compensationOperations).recordStep(
            event.orderId,
            OrderCompensationStepType.POINTS,
            OrderCompensationStepState.SUCCEEDED,
            null,
            event.cancelledAt,
        )
    }

    private fun cancellationEvent(): OrderCancelledV1 {
        val orderId = UUID.randomUUID()
        return OrderCancelledV1(
            envelope =
                EventEnvelope(
                    eventId = UUID.randomUUID(),
                    eventType = "OrderCancelledV1",
                    aggregateId = orderId,
                    aggregateVersion = 7,
                    occurredAt = NOW,
                    payloadVersion = 1,
                    correlationId = "cancel-$orderId",
                    causationId = "customer-cancellation-command:${UUID.randomUUID()}",
                ),
            orderId = orderId,
            cancelledAt = NOW,
            couponRequired = true,
            pointsRequired = true,
            couponPolicy = BenefitRestorationPolicySnapshotV1(41, "PRESERVE_ORIGINAL_EXPIRY", 30),
            pointsPolicy = BenefitRestorationPolicySnapshotV1(42, "PRESERVE_ORIGINAL_EXPIRY", 30),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T10:00:00Z")
    }
}
