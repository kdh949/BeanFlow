package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.internal.domain.CheckoutMode
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

internal class CustomerOrderPresentationPolicyTest {
    private val now = Instant.parse("2026-08-13T03:00:00Z")

    @Test
    fun `server owns active and past classification for every order state`() {
        val active = setOf(OrderState.PENDING_PAYMENT, OrderState.PAID, OrderState.ACCEPTED, OrderState.PREPARING, OrderState.READY)
        val past = setOf(OrderState.COMPLETED, OrderState.REJECTED, OrderState.EXPIRED, OrderState.CANCELLED)

        OrderState.entries.forEach { state ->
            assertThat(CustomerOrderPresentationPolicy.matches(state, CustomerOrderStatusFilter.ACTIVE))
                .isEqualTo(state in active)
            assertThat(CustomerOrderPresentationPolicy.matches(state, CustomerOrderStatusFilter.PAST))
                .isEqualTo(state in past)
        }
    }

    @Test
    fun `cancel is allowed only before the matching pending or paid deadline`() {
        val pending = facts(OrderState.PENDING_PAYMENT, reservationExpiresAt = now.plusSeconds(1))
        val paid = facts(OrderState.PAID, acceptanceDeadlineAt = now.plusSeconds(1))

        assertThat(CustomerOrderPresentationPolicy.allowedActions(pending, now))
            .containsExactly(CustomerOrderAllowedAction.CANCEL)
        assertThat(CustomerOrderPresentationPolicy.allowedActions(paid, now))
            .containsExactly(CustomerOrderAllowedAction.CANCEL)
        assertThat(
            CustomerOrderPresentationPolicy.allowedActions(
                pending.copy(reservationExpiresAt = now),
                now,
            ),
        ).isEmpty()
        assertThat(
            CustomerOrderPresentationPolicy.allowedActions(
                paid.copy(acceptanceDeadlineAt = now),
                now,
            ),
        ).isEmpty()
        assertThat(CustomerOrderPresentationPolicy.allowedActions(pending.copy(reservationExpiresAt = now.minusSeconds(1)), now))
            .isEmpty()
        assertThat(CustomerOrderPresentationPolicy.allowedActions(paid.copy(acceptanceDeadlineAt = now.minusSeconds(1)), now))
            .isEmpty()
    }

    @Test
    fun `immediate pending order is cancellable before at and after its payment cutoff without a reservation deadline`() {
        val pending = facts(OrderState.PENDING_PAYMENT, checkoutMode = CheckoutMode.IMMEDIATE, orderingWindowClosesAt = now)

        listOf(now.minusSeconds(1), now, now.plusSeconds(1)).forEach { queryTime ->
            assertThat(CustomerOrderPresentationPolicy.allowedActions(pending, queryTime))
                .containsExactly(CustomerOrderAllowedAction.CANCEL)
        }
    }

    @Test
    fun `immediate pending order with missing cutoff remains an explicit dependency failure`() {
        assertThatThrownBy {
            CustomerOrderPresentationPolicy.allowedActions(facts(OrderState.PENDING_PAYMENT, checkoutMode = CheckoutMode.IMMEDIATE), now)
        }.isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
            assertThat(failure.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            assertThat(failure.message).isEqualTo("Immediate order has no ordering cutoff")
        }
    }

    @Test
    fun `immediate paid order retains the acceptance deadline boundary`() {
        val paid = facts(OrderState.PAID, checkoutMode = CheckoutMode.IMMEDIATE, acceptanceDeadlineAt = now)
        assertThat(CustomerOrderPresentationPolicy.allowedActions(paid, now.minusSeconds(1)))
            .containsExactly(CustomerOrderAllowedAction.CANCEL)
        assertThat(CustomerOrderPresentationPolicy.allowedActions(paid, now)).isEmpty()
        assertThat(CustomerOrderPresentationPolicy.allowedActions(paid, now.plusSeconds(1))).isEmpty()
        assertThatThrownBy { CustomerOrderPresentationPolicy.allowedActions(paid.copy(acceptanceDeadlineAt = null), now) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
    }

    @ParameterizedTest
    @EnumSource(CheckoutMode::class)
    fun `terminal actions mirror reorder and customer refund commands`(checkoutMode: CheckoutMode) {
        assertThat(CustomerOrderPresentationPolicy.allowedActions(facts(OrderState.COMPLETED, checkoutMode = checkoutMode), now))
            .containsExactly(CustomerOrderAllowedAction.REORDER)
        assertThat(
            CustomerOrderPresentationPolicy.allowedActions(
                facts(OrderState.CANCELLED, checkoutMode = checkoutMode, cancellationCause = OrderCancellationCause.CUSTOMER_REQUEST),
                now,
            ),
        ).containsExactly(CustomerOrderAllowedAction.REORDER, CustomerOrderAllowedAction.VIEW_REFUND)
        assertThat(
            CustomerOrderPresentationPolicy.allowedActions(
                facts(OrderState.CANCELLED, checkoutMode = checkoutMode, cancellationCause = OrderCancellationCause.PAYMENT_DECLINED),
                now,
            ),
        ).containsExactly(CustomerOrderAllowedAction.REORDER)
    }

    @ParameterizedTest
    @EnumSource(CheckoutMode::class)
    fun `every noncancellable state exposes only commands supported by its lifecycle`(checkoutMode: CheckoutMode) {
        listOf(OrderState.ACCEPTED, OrderState.PREPARING, OrderState.READY).forEach { state ->
            assertThat(CustomerOrderPresentationPolicy.allowedActions(facts(state, checkoutMode = checkoutMode), now)).isEmpty()
        }
        listOf(OrderState.COMPLETED, OrderState.REJECTED, OrderState.EXPIRED).forEach { state ->
            assertThat(CustomerOrderPresentationPolicy.allowedActions(facts(state, checkoutMode = checkoutMode), now))
                .containsExactly(CustomerOrderAllowedAction.REORDER)
        }
    }

    @Test
    fun `missing deadline on a cancellable state is an explicit dependency failure`() {
        assertThatThrownBy {
            CustomerOrderPresentationPolicy.allowedActions(facts(OrderState.PENDING_PAYMENT), now)
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)

        assertThatThrownBy {
            CustomerOrderPresentationPolicy.allowedActions(facts(OrderState.PAID), now)
        }.isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
    }

    @Test
    fun `item summary uses the first snapshot line and a generic additional item count`() {
        assertThat(CustomerOrderPresentationPolicy.itemSummary(listOf("아이스 아메리카노")))
            .isEqualTo("아이스 아메리카노")
        assertThat(CustomerOrderPresentationPolicy.itemSummary(listOf("아이스 아메리카노", "크루아상", "오트 라떼")))
            .isEqualTo("아이스 아메리카노 외 2건")
        assertThatThrownBy { CustomerOrderPresentationPolicy.itemSummary(emptyList()) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
    }

    private fun facts(
        state: OrderState,
        checkoutMode: CheckoutMode = CheckoutMode.LEGACY_RESERVED,
        reservationExpiresAt: Instant? = null,
        orderingWindowClosesAt: Instant? = null,
        acceptanceDeadlineAt: Instant? = null,
        cancellationCause: OrderCancellationCause? = null,
    ) = CustomerOrderActionFacts(
        state = state,
        checkoutMode = checkoutMode,
        reservationExpiresAt = reservationExpiresAt,
        orderingWindowClosesAt = orderingWindowClosesAt,
        acceptanceDeadlineAt = acceptanceDeadlineAt,
        cancellationCause = cancellationCause,
    )
}
