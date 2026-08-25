package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageReferenceOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class StorefrontImageOrphanSweepTest {
    private val storage = mock(StorefrontImageStorageOperations::class.java)
    private val references = mock(StorefrontImageReferenceOperations::class.java)
    private val metrics = SimpleMeterRegistry()
    private val now = Instant.parse("2026-08-24T00:00:00Z")
    private val sweep = StorefrontImageOrphanSweep(storage, references, metrics, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `sweep rechecks current references and deletes only an old orphan`() {
        val referenced = "stores/id/hash/original.jpg"
        val orphan = "menus/id/hash/thumbnail.jpg"
        `when`(storage.listOrphanCandidates(now.minusSeconds(86_400), 100)).thenReturn(listOf(referenced, orphan))
        `when`(references.isReferenced(referenced)).thenReturn(true)
        `when`(references.isReferenced(orphan)).thenReturn(false)

        sweep.sweep()

        verify(storage, never()).deleteObject(referenced)
        verify(storage).deleteObject(orphan)
        assertThat(
            metrics
                .get("beanflow.media.orphan")
                .tag("outcome", "deleted")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(
            metrics
                .get("beanflow.media.orphan")
                .tag("outcome", "succeeded")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `sweep records and rethrows a provider failure`() {
        doThrow(IllegalStateException("AIStor list failed"))
            .`when`(storage)
            .listOrphanCandidates(now.minusSeconds(86_400), 100)

        assertThatThrownBy(sweep::sweep).isInstanceOf(IllegalStateException::class.java)
        assertThat(
            metrics
                .get("beanflow.media.orphan")
                .tag("outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}
