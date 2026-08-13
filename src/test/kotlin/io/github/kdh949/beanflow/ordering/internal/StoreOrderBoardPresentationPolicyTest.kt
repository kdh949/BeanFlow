package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

internal class StoreOrderBoardPresentationPolicyTest {
    private val warningAt = Instant.parse("2026-08-14T03:02:00Z")
    private val deadlineAt = Instant.parse("2026-08-14T03:03:00Z")

    @Test
    fun `active states map to lanes and server-owned actions`() {
        assertThat(policy(OrderState.PAID, warningAt.minusNanos(1)).lane).isEqualTo(StoreOrderBoardLane.PENDING_ACCEPTANCE)
        assertThat(policy(OrderState.PAID, warningAt.minusNanos(1)).acceptancePhase).isEqualTo(StoreOrderAcceptancePhase.OPEN)
        assertThat(policy(OrderState.PAID, warningAt).acceptancePhase).isEqualTo(StoreOrderAcceptancePhase.WARNING)
        assertThat(policy(OrderState.PAID, deadlineAt).acceptancePhase).isEqualTo(StoreOrderAcceptancePhase.TIMEOUT_PENDING)
        assertThat(policy(OrderState.PAID, deadlineAt).allowedActions).isEmpty()

        assertThat(policy(OrderState.ACCEPTED, warningAt).allowedActions).containsExactly(StoreOrderAction.START_PREPARING)
        assertThat(policy(OrderState.PREPARING, warningAt).allowedActions).containsExactly(StoreOrderAction.MARK_READY)
        assertThat(policy(OrderState.READY, warningAt).allowedActions).containsExactly(StoreOrderAction.COMPLETE)
        assertThat(policy(OrderState.COMPLETED, warningAt).lane).isNull()
        assertThat(policy(OrderState.COMPLETED, warningAt).allowedActions).isEmpty()
    }

    @Test
    fun `action and expected status have one valid source-state combination`() {
        assertThat(StoreOrderBoardPresentationPolicy.targetState(StoreOrderAction.ACCEPT, StoreOrderExpectedStatus.PAID))
            .isEqualTo(StoreOrderTargetState.ACCEPTED)
        assertThat(StoreOrderBoardPresentationPolicy.targetState(StoreOrderAction.REJECT, StoreOrderExpectedStatus.PAID))
            .isEqualTo(StoreOrderTargetState.REJECTED)
        assertThat(
            StoreOrderBoardPresentationPolicy.targetState(
                StoreOrderAction.START_PREPARING,
                StoreOrderExpectedStatus.ACCEPTED,
            ),
        ).isEqualTo(StoreOrderTargetState.PREPARING)
        assertThat(StoreOrderBoardPresentationPolicy.targetState(StoreOrderAction.MARK_READY, StoreOrderExpectedStatus.PREPARING))
            .isEqualTo(StoreOrderTargetState.READY)
        assertThat(StoreOrderBoardPresentationPolicy.targetState(StoreOrderAction.COMPLETE, StoreOrderExpectedStatus.READY))
            .isEqualTo(StoreOrderTargetState.COMPLETED)

        assertThatThrownBy {
            StoreOrderBoardPresentationPolicy.targetState(StoreOrderAction.COMPLETE, StoreOrderExpectedStatus.PAID)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ORDER_ACTION_NOT_ALLOWED)
        }
    }

    private fun policy(
        state: OrderState,
        now: Instant,
    ) = StoreOrderBoardPresentationPolicy.present(
        state = state,
        acceptanceWarningAt = if (state == OrderState.PAID) warningAt else null,
        acceptanceDeadlineAt = if (state == OrderState.PAID) deadlineAt else null,
        now = now,
    )
}
