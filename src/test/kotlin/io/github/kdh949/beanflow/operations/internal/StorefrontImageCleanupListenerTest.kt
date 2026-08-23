package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageCleanupRequestedV1
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock

internal class StorefrontImageCleanupListenerTest {
    private val storage = mock(StorefrontImageStorageOperations::class.java)
    private val metrics = SimpleMeterRegistry()
    private val listener = StorefrontImageCleanupListener(storage, metrics)
    private val event = StorefrontImageCleanupRequestedV1("stores/id/original.jpg", "stores/id/thumbnail.jpg")

    @Test
    fun `cleanup failure is measured and rethrown for persistent publication retry`() {
        doThrow(IllegalStateException("AIStor delete failed"))
            .`when`(storage)
            .delete(event.originalKey, event.thumbnailKey)

        assertThatThrownBy { listener.cleanup(event) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(
            metrics
                .get("beanflow.media.cleanup.publication")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}
