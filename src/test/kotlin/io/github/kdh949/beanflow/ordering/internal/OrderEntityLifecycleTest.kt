package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
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
        assertThat(order.cancellationReasonCode).isNull()
        assertThat(order.cancellationDetail).isNull()
    }

    @Test
    fun `customer cancellation normalizes detail and records the reason`() {
        val order = pendingOrder()
        val cancelledAt = paidAt.plusSeconds(10)

        order.cancelByCustomer(
            cancelledAt,
            CustomerCancellationReasonCode.CHANGED_MIND,
            "  ordered by mistake  ",
        )

        assertThat(order.state).isEqualTo(OrderState.CANCELLED)
        assertThat(order.cancelledAt).isEqualTo(cancelledAt)
        assertThat(order.cancellationCause).isEqualTo(OrderCancellationCause.CUSTOMER_REQUEST)
        assertThat(order.cancellationReasonCode).isEqualTo(CustomerCancellationReasonCode.CHANGED_MIND)
        assertThat(order.cancellationDetail).isEqualTo("ordered by mistake")
    }

    @Test
    fun `paid customer cancellation wins only before the acceptance deadline`() {
        val beforeDeadline = pendingOrder().also { it.markPaid(paidAt) }
        val atDeadline = pendingOrder().also { it.markPaid(paidAt) }
        val afterDeadline = pendingOrder().also { it.markPaid(paidAt) }

        beforeDeadline.cancelByCustomer(
            paidAt.plusSeconds(180).minusNanos(1),
            CustomerCancellationReasonCode.WAIT_TOO_LONG,
            null,
        )

        assertThat(beforeDeadline.state).isEqualTo(OrderState.CANCELLED)
        assertThatThrownBy {
            atDeadline.cancelByCustomer(
                paidAt.plusSeconds(180),
                CustomerCancellationReasonCode.WAIT_TOO_LONG,
                null,
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
        }
        assertThatThrownBy {
            afterDeadline.cancelByCustomer(
                paidAt.plusSeconds(180).plusNanos(1),
                CustomerCancellationReasonCode.WAIT_TOO_LONG,
                null,
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
        }
        assertThat(atDeadline.state).isEqualTo(OrderState.PAID)
        assertThat(afterDeadline.state).isEqualTo(OrderState.PAID)
    }

    @Test
    fun `customer cancellation rejects control characters`() {
        val order = pendingOrder()

        assertThatThrownBy {
            order.cancelByCustomer(
                paidAt.plusSeconds(10),
                CustomerCancellationReasonCode.OTHER,
                "unsafe\ntext",
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
        assertThat(order.state).isEqualTo(OrderState.PENDING_PAYMENT)
    }

    @Test
    fun `support cancellation preserves lifecycle facts and records a dedicated cause`() {
        val pending = pendingOrder()
        val paid = pendingOrder().also { it.markPaid(paidAt) }
        val accepted =
            pendingOrder().also {
                it.markPaid(paidAt)
                it.accept(paidAt.plusSeconds(60))
            }

        listOf(pending, paid, accepted).forEach { order ->
            order.cancelBySupport(paidAt.plusSeconds(70), CustomerCancellationReasonCode.OTHER, null)
            assertThat(order.state).isEqualTo(OrderState.CANCELLED)
            assertThat(order.cancellationCause).isEqualTo(OrderCancellationCause.SUPPORT_REQUEST)
        }

        val preparing =
            pendingOrder().also {
                it.markPaid(paidAt)
                it.accept(paidAt.plusSeconds(60))
                it.startPreparing(paidAt.plusSeconds(61))
            }
        assertThatThrownBy {
            preparing.cancelBySupport(paidAt.plusSeconds(70), CustomerCancellationReasonCode.OTHER, null)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
        }
        assertThat(preparing.state).isEqualTo(OrderState.PREPARING)
    }

    @Test
    fun `support pickup reschedule changes only the slot in eligible states`() {
        val order = pendingOrder()
        val previousSlot = order.pickupSlotId
        val nextSlot = UUID.randomUUID()

        order.reschedulePickupBySupport(paidAt.plusSeconds(10), nextSlot)

        assertThat(order.pickupSlotId).isEqualTo(nextSlot)
        assertThat(order.state).isEqualTo(OrderState.PENDING_PAYMENT)
        assertThat(order.payableKrw).isEqualTo(1_000)
        assertThat(previousSlot).isNotEqualTo(nextSlot)
        assertThatThrownBy { order.reschedulePickupBySupport(paidAt.plusSeconds(11), nextSlot) }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
            }
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
