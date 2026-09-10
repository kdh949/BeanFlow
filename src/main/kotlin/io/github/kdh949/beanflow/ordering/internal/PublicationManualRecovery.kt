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
) {
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
        return PublicationRecoveryView(
            id,
            row.eventType,
            row.listenerId,
            row.status ?: "PUBLISHED",
            row.attempts,
            row.completedAt,
            row.failed() && recoveryCase?.status == "MANUAL_REVIEW" &&
                targets.supports(
                    row.eventType,
                    row.listenerId,
                ),
            recoveryCase,
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
        if (!row.failed() ||
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
                "payload_hash, response_json, baseline_attempts, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)",
            id,
            c.actorId,
            c.publicationId,
            recoveryCase.caseId,
            c.key,
            hash,
            mapper.writeValueAsString(response),
            row.attempts,
            Timestamp.from(c.now),
        )
        return response
    }

    fun pending(): List<UUID> =
        jdbc.query(
            "SELECT r.id FROM ordering_manual_publication_recovery r LEFT JOIN event_publication p ON p.id = r.publication_id " +
                "WHERE r.status = 'RUNNING' AND (p.id IS NULL OR p.completion_date IS NOT NULL OR " +
                "((p.status = 'FAILED' OR p.status IS NULL) AND p.completion_attempts > r.baseline_attempts)) " +
                "ORDER BY r.created_at, r.id LIMIT 100",
            {
                rs,
                _,
                ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
        )

    fun reconcile(
        id: UUID,
        now: Instant,
    ) {
        val publicationId =
            jdbc
                .query(
                    "SELECT publication_id FROM ordering_manual_publication_recovery WHERE id = ? AND status = 'RUNNING'",
                    {
                        rs,
                        _,
                        ->
                        rs.getObject(
                            "publication_id",
                            UUID::class.java,
                        )
                    },
                    id,
                ).singleOrNull() ?: return
        val publication =
            find(
                publicationId,
                true,
            )
        val baseline =
            jdbc
                .query(
                    "SELECT baseline_attempts FROM ordering_manual_publication_recovery WHERE id = ? " +
                        "AND status = 'RUNNING' FOR UPDATE",
                    {
                        rs,
                        _,
                        ->
                        rs.getInt("baseline_attempts")
                    },
                    id,
                ).singleOrNull() ?: return
        val resolved = publication?.completedAt != null
        val failed =
            publication == null ||
                (publication.failed() && publication.attempts > baseline)
        if (!resolved && !failed) return
        cases.finish(
            ManualRecoveryKind.EVENT_PUBLICATION,
            publicationId,
            resolved,
            now,
        )
        jdbc.update(
            "UPDATE ordering_manual_publication_recovery SET status = ?, completed_at = ? WHERE id = ?",
            if (resolved) "RESOLVED" else "MANUAL_REVIEW",
            Timestamp.from(now),
            id,
        )
    }

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
                "WHERE status <> 'RUNNING' AND completed_at < ? ORDER BY completed_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}
