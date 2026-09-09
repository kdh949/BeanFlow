package io.github.kdh949.beanflow.ordering.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class EventPublicationRecoveryWorkerTest {
    private val now = Instant.parse("2026-09-10T00:00:00Z")
    private val publications = mock(IncompleteEventPublications::class.java)
    private val queries = mock(EventPublicationRecoveryQueries::class.java)
    private val manualReview = mock(EventPublicationManualReviewService::class.java)
    private val scope = AutomaticPublicationRecoveryScope()
    private val meters = SimpleMeterRegistry()
    private val worker =
        EventPublicationRecoveryWorker(publications, queries, manualReview, scope, Clock.fixed(now, ZoneOffset.UTC), meters, 100)

    @AfterEach
    fun closeMeters() {
        meters.close()
    }

    @Test
    fun `multiple handoff failures retain their causes while remaining work is attempted`() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val remainingId = UUID.randomUUID()
        val firstFailure = IllegalStateException("FIRST_HANDOFF_FAILURE")
        val secondFailure = IllegalStateException("SECOND_HANDOFF_FAILURE")
        `when`(queries.findExhaustedIds(100)).thenReturn(listOf(firstId, secondId, remainingId))
        `when`(manualReview.transition(firstId, now)).thenThrow(firstFailure)
        `when`(manualReview.transition(secondId, now)).thenThrow(secondFailure)
        `when`(queries.backlog()).thenReturn(PublicationBacklog(2, now, 6, 0))
        `when`(queries.manualReviewBacklog()).thenReturn(ManualReviewBacklog(0, null))

        val failure = catchThrowable { worker.runOnce() }
        assertThat(failure)
            .hasMessageContaining(firstId.toString())
            .hasCause(firstFailure)
        assertThat(failure.suppressed).hasSize(1)
        assertThat(failure.suppressed.single()).hasMessageContaining(secondId.toString()).hasCause(secondFailure)

        inOrder(manualReview, publications, queries).apply {
            verify(manualReview).transition(firstId, now)
            verify(manualReview).transition(secondId, now)
            verify(manualReview).transition(remainingId, now)
            verify(publications).resubmitIncompletePublications(any(ResubmissionOptions::class.java))
            verify(queries).backlog()
            verify(queries).manualReviewBacklog()
        }
        assertThat(scope.currentTime()).isNull()
    }

    @Test
    fun `automatic retry failure retains earlier handoff failure and clears query scope`() {
        val id = UUID.randomUUID()
        val handoffFailure = IllegalStateException("HANDOFF_FAILURE")
        val retryFailure = IllegalStateException("AUTOMATIC_RETRY_FAILURE")
        `when`(queries.findExhaustedIds(100)).thenReturn(listOf(id))
        `when`(manualReview.transition(id, now)).thenThrow(handoffFailure)
        doAnswer {
            assertThat(scope.currentTime()).isEqualTo(now)
            throw retryFailure
        }.`when`(publications).resubmitIncompletePublications(any(ResubmissionOptions::class.java))

        val failure = catchThrowable { worker.runOnce() }
        assertThat(failure).isSameAs(retryFailure)
        assertThat(failure.suppressed).hasSize(1)
        assertThat(failure.suppressed.single()).hasMessageContaining(id.toString()).hasCause(handoffFailure)
        assertThat(scope.currentTime()).isNull()
    }

    @Test
    fun `backlog query failure retains earlier handoff failure after retry dispatch`() {
        val id = UUID.randomUUID()
        val handoffFailure = IllegalStateException("HANDOFF_FAILURE")
        val metricsFailure = IllegalStateException("BACKLOG_QUERY_FAILURE")
        `when`(queries.findExhaustedIds(100)).thenReturn(listOf(id))
        `when`(manualReview.transition(id, now)).thenThrow(handoffFailure)
        doThrow(metricsFailure).`when`(queries).backlog()

        val failure = catchThrowable { worker.runOnce() }
        assertThat(failure).isSameAs(metricsFailure)
        assertThat(failure.suppressed).hasSize(1)
        assertThat(failure.suppressed.single()).hasMessageContaining(id.toString()).hasCause(handoffFailure)
        inOrder(publications, queries).apply {
            verify(publications).resubmitIncompletePublications(any(ResubmissionOptions::class.java))
            verify(queries).backlog()
        }
        assertThat(scope.currentTime()).isNull()
    }
}
