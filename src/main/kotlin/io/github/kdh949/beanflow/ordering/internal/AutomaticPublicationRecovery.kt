package io.github.kdh949.beanflow.ordering.internal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class AutomaticPublicationRecoveryScope {
    private val current = ThreadLocal<Instant>()
    private val candidates = ThreadLocal<Map<UUID, PublicationClaimSelection>>()

    fun remember(publications: List<TargetEventPublication>) {
        candidates.set(
            publications.associate {
                it.identifier to
                    PublicationClaimSelection(it.identifier, it.completionAttempts, (it as? PublicationClaimCandidate)?.manualRequestId)
            },
        )
    }

    fun selection(id: UUID): PublicationClaimSelection? = candidates.get()?.get(id)

    fun currentTime(): Instant? = current.get()

    fun <T> run(
        now: Instant,
        action: () -> T,
    ): T {
        check(current.get() == null) { "Automatic publication recovery scope is already active" }
        current.set(now)
        try {
            return action()
        } finally {
            current.remove()
            candidates.remove()
        }
    }
}

/** 기존 Modulith dispatch를 유지하며 claim 예산과 수동 시도 결과를 DB 원장으로 보호한다. */
@Component
@Primary
internal class AutomaticPublicationRecoveryRepository(
    @Qualifier("jpaEventPublicationRepository") private val delegate: EventPublicationRepository,
    private val scope: AutomaticPublicationRecoveryScope,
    private val queries: EventPublicationRecoveryQueries,
    private val execution: ManualPublicationExecutionService,
    private val invocation: ManualPublicationInvocationScope,
) : EventPublicationRepository by delegate {
    // Kotlin delegation does not forward Java default methods. Explicitly preserve the JPA lifecycle.
    override fun markProcessing(identifier: UUID) {
        val selected = invocation.current()
        if (selected == null) {
            delegate.markProcessing(identifier)
        } else {
            execution.processing(selected) { delegate.markProcessing(identifier) }
        }
    }

    override fun markFailed(identifier: UUID) {
        val selected = invocation.current()
        if (selected == null) delegate.markFailed(identifier) else execution.result(selected, "FAILED") { delegate.markFailed(identifier) }
    }

    override fun markResubmitted(
        identifier: UUID,
        resubmissionDate: Instant,
    ): Boolean {
        scope.selection(identifier)?.let { return execution.claim(it, resubmissionDate) }
        if (execution.hasManualRequest(identifier)) return false
        return delegate.markResubmitted(identifier, resubmissionDate)
    }

    override fun markCompleted(
        publication: TargetEventPublication,
        completionDate: Instant,
    ) {
        val selected = invocation.current()
        if (selected == null) {
            delegate.markCompleted(publication, completionDate)
        } else {
            execution.result(selected, "SUCCEEDED") { delegate.markCompleted(publication, completionDate) }
        }
    }

    override fun findCompletedPublications(): List<TargetEventPublication> = delegate.findCompletedPublications()

    override fun findByStatus(status: Status): List<TargetEventPublication> = delegate.findByStatus(status)

    override fun countByStatus(status: Status): Int =
        if (status == Status.RESUBMITTED) {
            execution.resubmittedCount()
        } else {
            delegate.countByStatus(status)
        }

    override fun findFailedPublications(criteria: EventPublicationRepository.FailedCriteria): List<TargetEventPublication> {
        val now = scope.currentTime() ?: return delegate.findFailedPublications(criteria)
        return queries.findDue(now, criteria).also(scope::remember)
    }
}
