package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderEntityLifecycleTest {
    private val paidAt = Instant.parse("2026-07-30T00:00:00Z")

    @Test
    fun `paid order follows the complete store lifecycle`() {
        val order = pendingOrder()

        order.markPaid(paidAt)
        order.accept(paidAt.plusSeconds(179))
        order.startPreparing(paidAt.plusSeconds(180))
        order.markReady(paidAt.plusSeconds(240))
        order.complete(paidAt.plusSeconds(300))

        assertThat(order.state).isEqualTo(OrderState.COMPLETED)
        assertThat(order.paidAt).isEqualTo(paidAt)
        assertThat(order.acceptanceWarningAt).isEqualTo(paidAt.plusSeconds(120))
        assertThat(order.acceptanceDeadlineAt).isEqualTo(paidAt.plusSeconds(180))
        assertThat(order.completedAt).isEqualTo(paidAt.plusSeconds(300))
    }

    @Test
    fun `acceptance fails at the exact three minute deadline`() {
        val order = pendingOrder()
        order.markPaid(paidAt)

        assertThatThrownBy { order.accept(paidAt.plusSeconds(180)) }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }
        assertThat(order.state).isEqualTo(OrderState.PAID)
    }

    @Test
    fun `accepted order cannot be rejected`() {
        val order = pendingOrder()
        order.markPaid(paidAt)
        order.accept(paidAt.plusSeconds(60))

        assertThatThrownBy { order.reject(paidAt.plusSeconds(70), "store reason") }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }
        assertThat(order.state).isEqualTo(OrderState.ACCEPTED)
    }

    @Test
    fun `warning request is applied only once after two minutes`() {
        val order = pendingOrder()
        order.markPaid(paidAt)

        assertThat(order.markAcceptanceWarningRequested(paidAt.plusSeconds(119))).isFalse()
        assertThat(order.markAcceptanceWarningRequested(paidAt.plusSeconds(120))).isTrue()
        assertThat(order.markAcceptanceWarningRequested(paidAt.plusSeconds(121))).isFalse()
        assertThat(order.acceptanceWarningRequestedAt).isEqualTo(paidAt.plusSeconds(120))
    }

    @Test
    fun `explicit payment decline records terminal cancellation evidence`() {
        val order = pendingOrder()
        val declinedAt = paidAt.plusSeconds(10)

        order.cancelAfterPaymentDeclined(declinedAt)

        assertThat(order.state).isEqualTo(OrderState.CANCELLED)
        assertThat(order.cancelledAt).isEqualTo(declinedAt)
        assertThat(order.cancellationCause).isEqualTo(OrderCancellationCause.PAYMENT_DECLINED)
        assertThat(order.reservationExpiresAt).isNull()
    }

    private fun pendingOrder(): OrderEntity =
        OrderEntity(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            pickupSlotId = UUID.randomUUID(),
            state = OrderState.PENDING_PAYMENT,
            subtotalKrw = 1_000,
            couponDiscountKrw = 0,
            pointsAppliedKrw = 0,
            payableKrw = 1_000,
            reservationExpiresAt = paidAt.plusSeconds(300),
            createdAt = paidAt,
            updatedAt = paidAt,
        )
}
