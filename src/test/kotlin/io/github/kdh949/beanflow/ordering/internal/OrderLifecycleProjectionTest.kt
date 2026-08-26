package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

internal class OrderLifecycleProjectionTest {
    private val paidAt = Instant.parse("2026-08-14T03:00:00Z")

    @Test
    fun `accepts the exact persisted prefix for the current state`() {
        val lifecycle =
            PersistedOrderLifecycle(
                paidAt = paidAt,
                acceptedAt = paidAt.plusSeconds(10),
                preparingAt = paidAt.plusSeconds(20),
                readyAt = null,
                completedAt = null,
            )

        OrderLifecycleProjection.validate(OrderState.PREPARING, lifecycle)
    }

    @Test
    fun `fails the projection when state and milestones contradict`() {
        val lifecycle = PersistedOrderLifecycle(paidAt, paidAt.plusSeconds(10), null, null, null)

        assertThatThrownBy { OrderLifecycleProjection.validate(OrderState.PAID, lifecycle) }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }
    }

    @Test
    fun `fails the projection when persisted milestones are out of order`() {
        val lifecycle = PersistedOrderLifecycle(paidAt, paidAt.minusSeconds(1), null, null, null)

        assertThatThrownBy { OrderLifecycleProjection.validate(OrderState.ACCEPTED, lifecycle) }
            .isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
            }
    }
}
