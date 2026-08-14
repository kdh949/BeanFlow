package io.github.kdh949.beanflow.operations.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class MerchantCredentialRetentionWorkerTest {
    @Test
    fun `cleanup failure is observable and the next run retries without bypassing the repository`() {
        val repository = mock(MerchantCredentialRetentionRepository::class.java)
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val worker = MerchantCredentialRetentionWorker(repository, Clock.fixed(now, ZoneOffset.UTC), registry)
        `when`(repository.purgeDue(now, 100))
            .thenThrow(IllegalStateException("injected retention failure"))
            .thenReturn(MerchantCredentialRetentionResult(2, now.minusSeconds(10)))

        assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException::class.java)
        assertThat(registry.counter("beanflow.operations.merchant_credential.retention.failure").count()).isEqualTo(1.0)
        assertThat(worker.runOnce()).isEqualTo(2)
        assertThat(registry.counter("beanflow.operations.merchant_credential.retention.deleted").count()).isEqualTo(2.0)
    }
}
