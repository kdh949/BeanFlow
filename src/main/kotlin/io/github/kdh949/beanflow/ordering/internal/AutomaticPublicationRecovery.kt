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
        }
    }
}

/** Keeps Modulith's claim/dispatch/completion lifecycle; only automatic candidate selection changes. */
@Component
@Primary
internal class AutomaticPublicationRecoveryRepository(
    @Qualifier("jpaEventPublicationRepository") private val delegate: EventPublicationRepository,
    private val scope: AutomaticPublicationRecoveryScope,
    private val queries: EventPublicationRecoveryQueries,
) : EventPublicationRepository by delegate {
    // Kotlin delegation does not forward Java default methods. Explicitly preserve the JPA lifecycle.
    override fun markProcessing(identifier: UUID) = delegate.markProcessing(identifier)

    override fun markFailed(identifier: UUID) = delegate.markFailed(identifier)

    override fun markResubmitted(
        identifier: UUID,
        resubmissionDate: Instant,
    ): Boolean = delegate.markResubmitted(identifier, resubmissionDate)

    override fun markCompleted(
        publication: TargetEventPublication,
        completionDate: Instant,
    ) = delegate.markCompleted(publication, completionDate)

    override fun findCompletedPublications(): List<TargetEventPublication> = delegate.findCompletedPublications()

    override fun findByStatus(status: Status): List<TargetEventPublication> = delegate.findByStatus(status)

    override fun countByStatus(status: Status): Int = delegate.countByStatus(status)

    override fun findFailedPublications(criteria: EventPublicationRepository.FailedCriteria): List<TargetEventPublication> {
        val now = scope.currentTime() ?: return delegate.findFailedPublications(criteria)
        return queries.findDue(now, criteria)
    }
}
