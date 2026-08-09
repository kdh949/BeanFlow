package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class OrderingIdempotencyRetentionWorkerTest {
    @Test
    fun `store purge failure does not suppress cancellation purge`() {
        val orderCreation = mock<OrderCreationIdempotencyRetentionService>()
        val store = mock<StoreCommandIdempotencyRetentionService>()
        val cancellation = mock<CancellationCommandIdempotencyRetentionService>()
        val registry = SimpleMeterRegistry()
        `when`(store.purgeDue(NOW, 100)).thenThrow(IllegalStateException("store unavailable"))
        `when`(cancellation.purgeDue(NOW, 100))
            .thenReturn(OrderingIdempotencyPurgeResult(3, NOW.minusSeconds(10), 7))
        `when`(orderCreation.purgeDue(NOW, 100)).thenReturn(OrderingIdempotencyPurgeResult(0, null, 0))
        val worker = worker(orderCreation, store, cancellation, registry)

        assertThat(worker.runOnce()).isEqualTo(3)
        assertThat(
            registry
                .get("beanflow.ordering.idempotency.retention.failure")
                .tag("table", "store_command")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("beanflow.ordering.idempotency.retention.deleted")
                .tag("table", "cancellation_command")
                .counter()
                .count(),
        ).isEqualTo(3.0)
    }

    @Test
    fun `cancellation purge failure does not roll back store purge`() {
        val orderCreation = mock<OrderCreationIdempotencyRetentionService>()
        val store = mock<StoreCommandIdempotencyRetentionService>()
        val cancellation = mock<CancellationCommandIdempotencyRetentionService>()
        val registry = SimpleMeterRegistry()
        `when`(store.purgeDue(NOW, 100))
            .thenReturn(OrderingIdempotencyPurgeResult(2, NOW.minusSeconds(20), 0))
        `when`(cancellation.purgeDue(NOW, 100)).thenThrow(IllegalStateException("cancellation unavailable"))
        `when`(orderCreation.purgeDue(NOW, 100)).thenReturn(OrderingIdempotencyPurgeResult(0, null, 0))
        val worker = worker(orderCreation, store, cancellation, registry)

        assertThat(worker.runOnce()).isEqualTo(2)
        assertThat(
            registry
                .get("beanflow.ordering.idempotency.retention.failure")
                .tag("table", "cancellation_command")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .get("beanflow.ordering.idempotency.retention.deleted")
                .tag("table", "store_command")
                .counter()
                .count(),
        ).isEqualTo(2.0)
    }

    private fun worker(
        orderCreation: OrderCreationIdempotencyRetentionService,
        store: StoreCommandIdempotencyRetentionService,
        cancellation: CancellationCommandIdempotencyRetentionService,
        registry: SimpleMeterRegistry,
    ) = OrderingIdempotencyRetentionWorker(
        orderCreationRecords = orderCreation,
        storeRecords = store,
        cancellationRecords = cancellation,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        meterRegistry = registry,
        chunkSize = 100,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T00:00:00Z")
    }
}
