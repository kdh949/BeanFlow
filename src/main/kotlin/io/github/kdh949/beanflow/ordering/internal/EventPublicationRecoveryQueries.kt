package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
internal class EventPublicationRecoveryQueries(
    private val jdbcTemplate: JdbcTemplate,
    private val serializer: EventSerializer,
) {
    fun findDue(
        now: Instant,
        criteria: EventPublicationRepository.FailedCriteria,
    ): List<TargetEventPublication> {
        val delays =
            EventPublicationRetrySchedule
                .delaySecondsByAttempt()
                .mapIndexed { attempt, seconds -> "WHEN $attempt THEN $seconds" }
                .joinToString(" ")
        val before = criteria.publicationDateReference ?: now
        val limit = criteria.maxItemsToRead
        require(limit > 0) { "Automatic recovery requires a bounded batch" }
        return jdbcTemplate.query(
            """
            SELECT p.*, (SELECT r.id FROM ordering_manual_publication_recovery r WHERE r.publication_id = p.id AND r.status = 'RUNNING') AS manual_request_id FROM event_publication p
            WHERE ($AUTOMATIC_CANDIDATE
              AND p.completion_attempts BETWEEN 0 AND ?
              AND p.publication_date < ?
              AND COALESCE(p.last_resubmission_date, p.publication_date)
                    + (CASE p.completion_attempts $delays END) * interval '1 second' <= ?)
              OR (p.completion_date IS NULL
                  AND EXISTS (SELECT 1 FROM ordering_manual_publication_recovery request
                      JOIN operations_reprocessing_case c ON c.id = request.case_id
                      WHERE request.publication_id = p.id AND request.status = 'RUNNING'
                        AND c.status = 'RUNNING' AND request.baseline_attempts = p.completion_attempts
                        AND request.claimed_at IS NULL
                        AND (p.status = 'FAILED' OR p.status IS NULL OR
                            (request.replay_unknown AND p.status IN ('PROCESSING', 'RESUBMITTED')))))
            ORDER BY p.publication_date, p.id LIMIT ?
            """.trimIndent(),
            { rs, _ -> publication(rs, rs.getObject("manual_request_id", UUID::class.java)) },
            EventPublicationRetrySchedule.maximumResubmissions,
            Timestamp.from(before),
            Timestamp.from(now),
            limit,
        )
    }

    fun validateManualPayload(id: UUID) {
        try {
            jdbcTemplate.query("SELECT * FROM event_publication WHERE id = ?", { rs, _ -> publication(rs) }, id).single()
        } catch (failure: RuntimeException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Persisted publication payload cannot be read",
                targetReference = id.toString(),
            )
        }
    }

    fun findExhaustedIds(batchSize: Int): List<UUID> =
        jdbcTemplate.query(
            """
            SELECT p.id FROM event_publication p
            WHERE $AUTOMATIC_CANDIDATE AND p.completion_attempts > ?
            ORDER BY p.publication_date, p.id LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            EventPublicationRetrySchedule.maximumResubmissions,
            batchSize,
        )

    fun lockExhausted(id: UUID): ExhaustedPublication? =
        jdbcTemplate
            .query(
                """
                SELECT p.*,
                       p.serialized_event::jsonb -> 'envelope' ->> 'eventId' AS event_id,
                       p.serialized_event::jsonb -> 'envelope' ->> 'correlationId' AS correlation_id
                FROM event_publication p
                WHERE p.id = ? AND $AUTOMATIC_CANDIDATE AND p.completion_attempts > ?
                FOR UPDATE OF p SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> ExhaustedPublication(publication(rs), rs.getString("event_id"), rs.getString("correlation_id")) },
                id,
                EventPublicationRetrySchedule.maximumResubmissions,
            ).singleOrNull()

    fun backlog(): PublicationBacklog =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) AS pending, min(p.publication_date) AS oldest,
                       COALESCE(max(p.completion_attempts), 0) AS attempts,
                       count(*) FILTER (WHERE $AUTOMATIC_CANDIDATE
                                          AND p.completion_attempts BETWEEN 0 AND ?) AS retry_pending
                FROM event_publication p WHERE p.completion_date IS NULL
                """.trimIndent(),
                { rs, _ ->
                    PublicationBacklog(
                        rs.getLong("pending"),
                        rs.getTimestamp("oldest")?.toInstant(),
                        rs.getLong("attempts"),
                        rs.getLong("retry_pending"),
                    )
                },
                EventPublicationRetrySchedule.maximumResubmissions,
            ),
        )

    fun manualReviewBacklog(): ManualReviewBacklog =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) AS pending, min(created_at) AS oldest
                FROM operations_reprocessing_case
                WHERE case_type = 'EVENT_PUBLICATION' AND status <> 'RESOLVED'
                """.trimIndent(),
                { rs, _ -> ManualReviewBacklog(rs.getLong("pending"), rs.getTimestamp("oldest")?.toInstant()) },
            ),
        )

    private fun publication(
        rs: ResultSet,
        requestId: UUID? = null,
    ): TargetEventPublication =
        RecoveryPublication(
            id = rs.getObject("id", UUID::class.java),
            payload = serializer.deserialize(rs.getString("serialized_event"), Class.forName(rs.getString("event_type"))),
            target = PublicationTargetIdentifier.of(rs.getString("listener_id")),
            publishedAt = rs.getTimestamp("publication_date").toInstant(),
            state = EventPublication.Status.valueOf(rs.getString("status") ?: "PUBLISHED"),
            attempts = rs.getInt("completion_attempts"),
            resubmittedAt = rs.getTimestamp("last_resubmission_date")?.toInstant(),
            manualRequestId = requestId,
        )

    private companion object {
        const val AUTOMATIC_CANDIDATE = """
            p.completion_date IS NULL AND (p.status = 'FAILED' OR p.status IS NULL)
            AND p.listener_id NOT LIKE 'beanflow.analytics.%'
            AND NOT EXISTS (
                SELECT 1 FROM operations_reprocessing_case c
                WHERE c.case_type = 'EVENT_PUBLICATION'
                  AND c.owner_reference = 'event-publication:' || p.id::text
            )
        """
    }
}

internal data class ExhaustedPublication(
    val publication: TargetEventPublication,
    val eventId: String?,
    val correlationId: String?,
)

internal data class PublicationBacklog(
    val pending: Long,
    val oldest: Instant?,
    val attempts: Long,
    val retryPending: Long,
)

internal data class ManualReviewBacklog(
    val pending: Long,
    val oldest: Instant?,
)

private class RecoveryPublication(
    private val id: UUID,
    private val payload: Any,
    private val target: PublicationTargetIdentifier,
    private val publishedAt: Instant,
    private var state: EventPublication.Status,
    private val attempts: Int,
    private val resubmittedAt: Instant?,
    override val manualRequestId: UUID?,
) : TargetEventPublication,
    PublicationClaimCandidate {
    private var completedAt: Instant? = null

    override fun getIdentifier(): UUID = id

    override fun getEvent(): Any = payload

    override fun getTargetIdentifier(): PublicationTargetIdentifier = target

    override fun getPublicationDate(): Instant = publishedAt

    override fun getStatus(): EventPublication.Status = state

    override fun getCompletionAttempts(): Int = attempts

    override fun getLastResubmissionDate(): Instant? = resubmittedAt

    override fun getCompletionDate(): Optional<Instant> = Optional.ofNullable(completedAt)

    override fun markCompleted(instant: Instant) {
        completedAt = instant
        state = EventPublication.Status.COMPLETED
    }
}
