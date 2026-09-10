package io.github.kdh949.beanflow.ordering.internal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.modulith.events.core.EventPublicationRegistry
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Predicate

internal interface PublicationClaimCandidate {
    val manualRequestId: UUID?
}

internal data class PublicationClaimSelection(
    val publicationId: UUID,
    val baseline: Int,
    val requestId: UUID?,
)

/** 호출 식별자 전달 전용이다. 실행 상태와 예산은 모두 DB 원장이 결정한다. */
@Component
internal class ManualPublicationInvocationScope {
    private val current = ThreadLocal<PublicationClaimSelection>()

    fun current(): PublicationClaimSelection? = current.get()

    fun <T> run(
        selection: PublicationClaimSelection,
        action: () -> T,
    ): T {
        check(current.get() == null) { "Publication invocation scope is already active" }
        current.set(selection)
        try {
            return action()
        } finally {
            current.remove()
        }
    }
}

/** Modulith의 dispatch/transaction/advisor는 유지하고 수동 시도의 callback identity만 전달한다. */
@Component
@Primary
internal class ManualPublicationExecutionRegistry(
    @Qualifier("eventPublicationRegistry") private val delegate: EventPublicationRegistry,
    private val scope: ManualPublicationInvocationScope,
) : EventPublicationRegistry by delegate {
    private class InvocationKey(
        val event: Any,
        val target: PublicationTargetIdentifier,
    ) {
        override fun equals(other: Any?) = other is InvocationKey && event === other.event && target == other.target

        override fun hashCode() = 31 * System.identityHashCode(event) + target.hashCode()
    }

    private val invocations = ConcurrentHashMap<InvocationKey, PublicationClaimSelection>()

    override fun processFailedPublications(
        options: ResubmissionOptions,
        consumer: Consumer<TargetEventPublication>,
    ) {
        delegate.processFailedPublications(options, tracked(consumer))
    }

    override fun processIncompletePublications(
        filter: Predicate<EventPublication>,
        consumer: Consumer<TargetEventPublication>,
        duration: Duration?,
    ) {
        delegate.processIncompletePublications(filter, tracked(consumer), duration)
    }

    private fun tracked(consumer: Consumer<TargetEventPublication>) =
        Consumer<TargetEventPublication> { publication ->
            val request = (publication as? PublicationClaimCandidate)?.manualRequestId
            val key = InvocationKey(publication.event, publication.targetIdentifier)
            if (request != null) {
                invocations[key] = PublicationClaimSelection(publication.identifier, publication.completionAttempts, request)
            }
            try {
                consumer.accept(publication)
            } catch (failure: Throwable) {
                invocations.remove(key)
                throw failure
            }
        }

    override fun markProcessing(
        event: Any,
        identifier: PublicationTargetIdentifier,
    ) = transition(event, identifier, false) { delegate.markProcessing(event, identifier) }

    override fun markFailed(
        event: Any,
        identifier: PublicationTargetIdentifier,
    ) = transition(event, identifier, true) { delegate.markFailed(event, identifier) }

    override fun markCompleted(
        event: Any,
        identifier: PublicationTargetIdentifier,
    ) = transition(event, identifier, true) { delegate.markCompleted(event, identifier) }

    private fun transition(
        event: Any,
        target: PublicationTargetIdentifier,
        terminal: Boolean,
        action: () -> Unit,
    ) {
        val key = InvocationKey(event, target)
        val selection = invocations[key]
        if (selection == null) {
            action()
            return
        }
        scope.run(selection, action)
        if (terminal) invocations.remove(key, selection)
    }
}

@Service
internal class ManualPublicationExecutionService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(
        selection: PublicationClaimSelection,
        now: Instant,
    ): Boolean {
        val row = lockedPublication(selection.publicationId) ?: return false
        if (row.completed || row.attempts != selection.baseline || row.attempts == Int.MAX_VALUE) return false
        if (selection.requestId == null) {
            val hasCase =
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM operations_reprocessing_case WHERE case_type = 'EVENT_PUBLICATION' AND owner_reference = ?)",
                    Boolean::class.java,
                    "event-publication:${selection.publicationId}",
                ) == true
            if (hasCase || row.status !in listOf(null, "FAILED")) return false
        } else {
            val permit =
                jdbc
                    .query(
                        "SELECT r.replay_unknown FROM ordering_manual_publication_recovery r JOIN " +
                            "operations_reprocessing_case c ON c.id = r.case_id " +
                            "WHERE r.id = ? AND r.publication_id = ? AND r.status = 'RUNNING' AND r.claimed_at IS NULL " +
                            "AND r.baseline_attempts = ? AND c.status = 'RUNNING' AND c.version = r.case_version FOR UPDATE OF r",
                        { rs, _ -> rs.getBoolean("replay_unknown") },
                        selection.requestId,
                        selection.publicationId,
                        selection.baseline,
                    ).singleOrNull() ?: return false
            val failed = row.status == null || row.status == "FAILED"
            val permittedUnknown = permit && row.status in listOf("PROCESSING", "RESUBMITTED")
            if (!failed && !permittedUnknown) return false
            jdbc.update(
                "UPDATE ordering_manual_publication_recovery SET claimed_at = ? WHERE id = ?",
                Timestamp.from(now),
                selection.requestId,
            )
        }
        // publication lock이 baseline 비교와 증가를 직렬화한다. SQL NULL도 명시적으로 claim한다.
        jdbc.update(
            "UPDATE event_publication SET status = 'RESUBMITTED', completion_attempts = completion_attempts + 1, last_resubmission_date = ? WHERE id = ?",
            Timestamp.from(now),
            selection.publicationId,
        )
        return true
    }

    fun hasManualRequest(id: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ordering_manual_publication_recovery WHERE publication_id = ?)",
            Boolean::class.java,
            id,
        ) == true

    @Transactional(propagation = Propagation.MANDATORY)
    fun processing(
        selection: PublicationClaimSelection,
        action: () -> Unit,
    ) {
        val row = lockedPublication(selection.publicationId)
        lockRequest(selection)
        jdbc.update(
            "UPDATE ordering_manual_publication_recovery SET started_at = COALESCE(started_at, ?) WHERE id = ?",
            Timestamp.from(clock.instant()),
            selection.requestId,
        )
        if (row != null && !row.completed && row.attempts == selection.baseline + 1) action()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun result(
        selection: PublicationClaimSelection,
        outcome: String,
        action: () -> Unit,
    ) {
        val row = lockedPublication(selection.publicationId)
        lockRequest(selection)
        val changed =
            jdbc.update(
                "UPDATE ordering_manual_publication_recovery SET execution_outcome = ?, result_pending = true WHERE id = ? " +
                    "AND (execution_outcome IS NULL OR execution_outcome = 'UNKNOWN')",
                outcome,
                selection.requestId,
            )
        // 늦은 callback는 자기 request에만 남긴다. 새 시도의 publication 결과는 덮지 않는다.
        if (changed == 1 && row != null && !row.completed && row.attempts == selection.baseline + 1) action()
    }

    private fun lockRequest(selection: PublicationClaimSelection) {
        check(
            jdbc
                .query(
                    "SELECT id FROM ordering_manual_publication_recovery WHERE id = ? AND publication_id = ? AND baseline_attempts = ? FOR UPDATE",
                    { rs, _ -> rs.getObject("id", UUID::class.java) },
                    selection.requestId,
                    selection.publicationId,
                    selection.baseline,
                ).singleOrNull() != null,
        ) { "Manual publication invocation request is missing" }
    }

    private data class PublicationRow(
        val attempts: Int,
        val status: String?,
        val completed: Boolean,
    )

    fun resubmittedCount(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM event_publication p WHERE p.status = 'RESUBMITTED' AND NOT EXISTS " +
                "(SELECT 1 FROM ordering_manual_publication_recovery r WHERE r.publication_id = p.id " +
                "AND r.execution_outcome = 'UNKNOWN' AND r.baseline_attempts::bigint + 1 = p.completion_attempts)",
            Int::class.java,
        )!!

    private fun lockedPublication(id: UUID): PublicationRow? =
        jdbc
            .query(
                "SELECT completion_attempts, status, completion_date FROM event_publication WHERE id = ? FOR UPDATE",
                { rs, _ ->
                    PublicationRow(
                        rs.getInt("completion_attempts"),
                        rs.getString("status"),
                        rs.getTimestamp("completion_date") != null,
                    )
                },
                id,
            ).singleOrNull()
}
