package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ManualRecoveryAcceptance
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseOperations
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseView
import io.github.kdh949.beanflow.operations.api.ManualRecoveryKind
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class RetryPublicationRequest(
    @field:Min(0) val expectedCaseVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown recovery field: $name")
}

internal data class PublicationRecoveryView(
    val publicationId: UUID,
    val eventType: String,
    val listenerId: String,
    val status: String,
    val attemptCount: Int,
    val completedAt: Instant?,
    val recoverable: Boolean,
    val recoveryCase: ManualRecoveryCaseView?,
    val executionOutcome: String?,
    val retryBlockedReason: String?,
)

internal data class RetryPublicationCommand(
    val actorId: UUID,
    val publicationId: UUID,
    val key: String,
    val expectedCaseVersion: Long,
    val reason: String,
    val now: Instant,
)

private data class PublicationRecoveryRow(
    val id: UUID,
    val eventType: String,
    val listenerId: String,
    val status: String?,
    val attempts: Int,
    val completedAt: Instant?,
) {
    fun failed() =
        completedAt == null && (
            status == null ||
                status == "FAILED"
        )
}

@Service
@Transactional
internal class PublicationManualRecoveryService(
    private val cases: ManualRecoveryCaseOperations,
    private val grants: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val correlation: CorrelationIdSource,
    private val targets: ManualPublicationTargetRegistry,
    private val queries: EventPublicationRecoveryQueries,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${beanflow.publication-manual-recovery.stale-after:PT5M}") private val staleAfter: Duration,
) {
    init {
        require(!staleAfter.isZero && !staleAfter.isNegative) { "Publication stale detection duration must be positive" }
    }

    fun list(
        actorId: UUID,
        cursor: String?,
        limit: Int,
    ) = run {
        grants.requireActive(
            actorId,
            OperatorPermission.EVENT_PUBLICATION_RECOVERY_READ,
        )
        cases.list(
            ManualRecoveryKind.EVENT_PUBLICATION,
            actorId,
            cursor,
            limit,
        )
    }

    fun get(
        actorId: UUID,
        id: UUID,
    ): PublicationRecoveryView {
        grants.requireActive(
            actorId,
            OperatorPermission.EVENT_PUBLICATION_RECOVERY_READ,
        )
        val row =
            find(
                id,
                false,
            ) ?: throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Publication was not found",
            )
        val recoveryCase =
            cases.find(
                ManualRecoveryKind.EVENT_PUBLICATION,
                id,
            )
        val unknown = hasUnknownExecution(id)
        val eligible =
            ((row.failed() && !unknown) || (unknown && targets.supportsUnknownReplay(row.eventType, row.listenerId))) &&
                row.completedAt == null && row.attempts < Int.MAX_VALUE && recoveryCase?.status == "MANUAL_REVIEW" &&
                targets.supports(row.eventType, row.listenerId)
        return PublicationRecoveryView(
            id,
            row.eventType,
            row.listenerId,
            row.status ?: "PUBLISHED",
            row.attempts,
            row.completedAt,
            eligible,
            recoveryCase,
            latestOutcome(id),
            if (unknown && row.completedAt == null && !eligible) "UNKNOWN_EXECUTION_REQUIRES_OWNER_RECONCILIATION" else null,
        )
    }

    fun retry(c: RetryPublicationCommand): ManualRecoveryAcceptance {
        grants.requireActive(
            c.actorId,
            OperatorPermission.EVENT_PUBLICATION_RECOVERY_RETRY,
        )
        if (!validText(
                c.key,
                8,
                128,
            ) ||
            !validText(
                c.reason,
                1,
                500,
            ) ||
            c.expectedCaseVersion < 0
        ) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Publication recovery input is invalid",
            )
        }
        val hash =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsBytes(
                        listOf(
                            c.publicationId,
                            c.expectedCaseVersion,
                            c.reason,
                        ),
                    ),
                ),
            )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "publication-recovery:${c.actorId}:${c.key}",
        )
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM ordering_manual_publication_recovery " +
                    "WHERE actor_id = ? AND idempotency_key = ?",
                {
                    rs,
                    _,
                    ->
                    rs.getString("payload_hash") to rs.getString("response_json")
                },
                c.actorId,
                c.key,
            ).singleOrNull()
            ?.let {
                if (it.first != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Publication recovery key has another payload",
                    )
                }
                return mapper.readValue(
                    it.second,
                    ManualRecoveryAcceptance::class.java,
                )
            }
        val row =
            find(
                c.publicationId,
                true,
            ) ?: throw DomainFailure(
                FailureCode.RESOURCE_NOT_FOUND,
                "Publication was not found",
            )
        val unknown = hasUnknownExecution(row.id)
        val replayUnknown = unknown && targets.supportsUnknownReplay(row.eventType, row.listenerId)
        if (row.completedAt != null || (!row.failed() && !replayUnknown) || (unknown && !replayUnknown) ||
            row.attempts >= Int.MAX_VALUE ||
            !targets.supports(
                row.eventType,
                row.listenerId,
            )
        ) {
            throw DomainFailure(
                FailureCode.RESOURCE_STATE_CONFLICT,
                "Publication is not failed or its exact listener is unsupported",
            )
        }
        queries.validateManualPayload(row.id)
        val recoveryCase =
            cases.begin(
                ManualRecoveryKind.EVENT_PUBLICATION,
                c.publicationId,
                c.expectedCaseVersion,
                c.now,
            )
        val id = UUID.randomUUID()
        val response =
            ManualRecoveryAcceptance(
                id,
                recoveryCase,
                c.now,
            )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = c.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "PUBLICATION_RETRY_REQUESTED",
                    targetType = "EventPublication",
                    targetId = c.publicationId,
                    occurredAt = c.now,
                    reason = c.reason,
                    beforeSummary =
                        mapOf(
                            "state" to "MANUAL_REVIEW",
                            "caseVersion" to c.expectedCaseVersion.toString(),
                        ),
                    afterSummary =
                        mapOf(
                            "state" to "RUNNING",
                            "caseVersion" to recoveryCase.version.toString(),
                        ),
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "publication-recovery:$id",
                ),
            ),
        )
        jdbc.update(
            "INSERT INTO ordering_manual_publication_recovery(id, actor_id, publication_id, case_id, idempotency_key, " +
                "payload_hash, response_json, baseline_attempts, status, created_at, case_version, replay_unknown) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?)",
            id,
            c.actorId,
            c.publicationId,
            recoveryCase.caseId,
            c.key,
            hash,
            mapper.writeValueAsString(response),
            row.attempts,
            Timestamp.from(c.now),
            recoveryCase.version,
            replayUnknown,
        )
        return response
    }

    private fun hasUnknownExecution(id: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ordering_manual_publication_recovery WHERE publication_id = ? AND execution_outcome = 'UNKNOWN')",
            Boolean::class.java,
            id,
        ) == true

    private fun latestOutcome(id: UUID): String? =
        jdbc
            .query(
                "SELECT execution_outcome FROM ordering_manual_publication_recovery WHERE publication_id = ? " +
                    "ORDER BY baseline_attempts DESC, created_at DESC, id DESC LIMIT 1",
                { rs, _ -> rs.getString("execution_outcome") },
                id,
            ).firstOrNull()

    fun pending(): List<UUID> =
        jdbc.query(
            "SELECT r.id FROM ordering_manual_publication_recovery r LEFT JOIN event_publication p ON p.id = r.publication_id " +
                "WHERE (r.status = 'RUNNING' OR r.execution_outcome = 'UNKNOWN' OR r.result_pending) AND (" +
                "r.execution_outcome IN ('SUCCEEDED', 'FAILED') OR (r.status = 'RUNNING' AND p.id IS NULL) OR " +
                "(p.completion_attempts = r.baseline_attempts::bigint + 1 AND (p.completion_date IS " +
                "NOT NULL OR p.status = 'FAILED')) OR " +
                "(r.status = 'RUNNING' AND p.completion_attempts > r.baseline_attempts AND " +
                "(COALESCE(r.started_at, r.claimed_at, p.last_resubmission_date) IS NULL OR " +
                "COALESCE(r.started_at, r.claimed_at, p.last_resubmission_date) <= ?))) " +
                "ORDER BY r.created_at, r.id LIMIT 100",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            Timestamp.from(clock.instant().minus(staleAfter)),
        )

    fun reconcile(
        id: UUID,
        now: Instant,
    ) {
        val publicationId =
            jdbc
                .query(
                    "SELECT publication_id FROM ordering_manual_publication_recovery WHERE id = ? AND (status = 'RUNNING' OR execution_outcome = 'UNKNOWN' OR result_pending)",
                    { rs, _ -> rs.getObject("publication_id", UUID::class.java) },
                    id,
                ).singleOrNull() ?: return
        val publication = find(publicationId, true)
        val request =
            jdbc
                .query(
                    "SELECT baseline_attempts, case_version, execution_outcome, status, started_at, " +
                        "claimed_at FROM ordering_manual_publication_recovery " +
                        "WHERE id = ? AND (status = 'RUNNING' OR execution_outcome = 'UNKNOWN' OR result_pending) FOR UPDATE",
                    { rs, _ ->
                        RecoveryAttempt(
                            rs.getInt("baseline_attempts"),
                            rs.getLong("case_version"),
                            rs.getString("execution_outcome"),
                            rs.getString("status"),
                            rs.getTimestamp("started_at")?.toInstant() ?: rs.getTimestamp("claimed_at")?.toInstant(),
                        )
                    },
                    id,
                ).singleOrNull() ?: return
        val sameAttempt = publication != null && publication.attempts.toLong() == request.baseline.toLong() + 1
        val outcome =
            when {
                request.outcome in setOf("SUCCEEDED", "FAILED") -> request.outcome!!

                sameAttempt && publication.completedAt != null -> "SUCCEEDED"

                sameAttempt && publication.failed() -> "FAILED"

                request.status == "RUNNING" && (
                    publication == null || (
                        publication.attempts > request.baseline &&
                            (request.startedAt == null || !request.startedAt.isAfter(now.minus(staleAfter)))
                    )
                ) -> "UNKNOWN"

                else -> return
            }
        val updated = cases.recordPublicationOutcome(publicationId, request.caseVersion, outcome, now)
        if (outcome == "UNKNOWN" && request.outcome != "UNKNOWN") {
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = "publication-recovery-worker",
                        actorType = AuditActorType.SYSTEM,
                        category = AuditCategory.OPERATIONS_POLICY,
                        action = "PUBLICATION_EXECUTION_UNKNOWN",
                        targetType = "EventPublication",
                        targetId = publicationId,
                        occurredAt = now,
                        reason = "EXECUTION_OUTCOME_UNKNOWN",
                        afterSummary = mapOf("state" to "MANUAL_REVIEW", "outcome" to "UNKNOWN"),
                        correlationId = "publication-recovery:$id",
                        sourceReference = "publication-recovery:$id:unknown",
                    ),
                ),
            )
        }
        jdbc.update(
            "UPDATE ordering_manual_publication_recovery SET status = ?, execution_outcome = ?, " +
                "completed_at = ?, result_pending = false, " +
                "unknown_since = CASE WHEN ? = 'UNKNOWN' THEN COALESCE(unknown_since, ?) ELSE unknown_since END, case_version = ? WHERE id = ?",
            if (outcome == "SUCCEEDED") "RESOLVED" else "MANUAL_REVIEW",
            outcome,
            Timestamp.from(now),
            outcome,
            Timestamp.from(now),
            updated?.version ?: request.caseVersion,
            id,
        )
    }

    private data class RecoveryAttempt(
        val baseline: Int,
        val caseVersion: Long,
        val outcome: String?,
        val status: String,
        val startedAt: Instant?,
    )

    private fun find(
        id: UUID,
        lock: Boolean,
    ): PublicationRecoveryRow? =
        jdbc
            .query(
                "SELECT id, event_type, listener_id, status, completion_attempts, completion_date FROM event_publication " +
                    "WHERE id = ?" + if (lock) " FOR UPDATE" else "",
                ::map,
                id,
            ).singleOrNull()

    private fun map(
        rs: ResultSet,
        row: Int,
    ) = PublicationRecoveryRow(
        rs.getObject(
            "id",
            UUID::class.java,
        ),
        rs.getString("event_type"),
        rs.getString("listener_id"),
        rs.getString("status"),
        rs.getInt("completion_attempts"),
        rs.getTimestamp("completion_date")?.toInstant(),
    )

    private fun validText(
        value: String,
        min: Int,
        max: Int,
    ) = value.length in min..max && value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/event-publication-recoveries")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class PublicationManualRecoveryController(
    private val service: PublicationManualRecoveryService,
    private val clock: Clock,
) {
    @GetMapping fun list(
        actor: OperatorActor,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(
        actor.actorId,
        cursor,
        limit,
    )

    @GetMapping("/{publicationId}")
    fun get(
        actor: OperatorActor,
        @PathVariable publicationId: UUID,
    ) = service.get(
        actor.actorId,
        publicationId,
    )

    @PostMapping("/{publicationId}/retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(
        actor: OperatorActor,
        @PathVariable publicationId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: RetryPublicationRequest,
    ) = service.retry(
        RetryPublicationCommand(
            actor.actorId,
            publicationId,
            key,
            request.expectedCaseVersion,
            request.reason,
            clock.instant(),
        ),
    )
}

@Component
internal class PublicationManualResultWorker(
    private val service: PublicationManualRecoveryService,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.publication-manual-recovery.fixed-delay-ms:10000}",
        initialDelayString = "\${beanflow.publication-manual-recovery.initial-delay-ms:30000}",
    )
    fun runOnce() {
        val failures = mutableListOf<RuntimeException>()
        service.pending().forEach { id ->
            try {
                service.reconcile(
                    id,
                    clock.instant(),
                )
            } catch (failure: RuntimeException) {
                failures +=
                    IllegalStateException(
                        "Publication recovery result reconciliation failed: $id",
                        failure,
                    )
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

@Component
internal class PublicationManualCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.publication-manual-recovery.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.publication-manual-recovery.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM ordering_manual_publication_recovery WHERE id IN (SELECT id FROM ordering_manual_publication_recovery " +
                "WHERE status <> 'RUNNING' AND execution_outcome IS DISTINCT FROM 'UNKNOWN' AND completed_at < ? ORDER BY completed_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}
