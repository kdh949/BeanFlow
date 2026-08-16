package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexRebuildOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexRebuildResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class OperatorSearchIndexRebuildCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val reason: String,
    val now: Instant,
)

/**
 * Coordinates the Operations command without creating one transaction around the whole rebuild.
 *
 * Discovery owns the source/index writes and commits one transaction per store. Operations claims
 * the replay key and appends the request audit before that pass begins, then stores the completed
 * response afterward. A process that stops while the pass is running leaves an explicit RUNNING
 * ledger row; it is never silently rerun or converted to a successful empty result.
 */
@Service
internal class OperatorSearchIndexRebuildService(
    private val commands: SearchIndexRebuildCommandLedger,
    private val rebuilds: StoreSearchIndexRebuildOperations,
    private val clock: Clock,
) {
    fun rebuild(command: OperatorSearchIndexRebuildCommand): StoreSearchIndexRebuildResult {
        // `claim` and `complete` are REQUIRES_NEW proxies, so their commit runs after the method
        // body returns. A commit timeout or a lost connection therefore surfaces at the call site,
        // not inside the ledger. Catching it here keeps the documented "outcome unknown" contract:
        // the global handler maps DataAccessException to 503 but leaves TransactionException at 500.
        val claim =
            try {
                commands.claim(command)
            } catch (failure: TransactionException) {
                throw outcomeUnknown("Search index rebuild command could not be claimed", failure)
            }
        return when (claim) {
            is SearchIndexRebuildClaim.Replay -> {
                claim.result
            }

            is SearchIndexRebuildClaim.Started -> {
                rebuildClaimed(claim.commandId, claim.attempt)
            }

            is SearchIndexRebuildClaim.Exhausted -> {
                commands.escalate(claim.commandId, clock.instant())
                throw manualReviewRequired()
            }
        }
    }

    private fun rebuildClaimed(
        commandId: UUID,
        attempt: Int,
    ): StoreSearchIndexRebuildResult {
        val result =
            try {
                rebuilds.rebuildAll()
            } catch (failure: DomainFailure) {
                failAfter(commandId, attempt, failure)
            } catch (failure: DataAccessException) {
                failAfter(commandId, attempt, rebuildFailed(failure))
            } catch (failure: RuntimeException) {
                failAfter(commandId, attempt, rebuildFailed(failure))
            }
        try {
            commands.complete(commandId, result, clock.instant())
        } catch (failure: TransactionException) {
            // The stores were rebuilt but the outcome row may or may not be committed. Reporting
            // success here would publish a result the ledger cannot replay. The UNKNOWN write is
            // conditional on the row still being RUNNING, so a COMPLETED that did commit wins.
            markUnknown(commandId)
            throw outcomeUnknown("Search index rebuild outcome could not be recorded", failure)
        }
        return result
    }

    /**
     * A failed pass keeps its ledger row. Deleting it would drop the payload binding that makes a
     * later same-key/different-reason request an IDEMPOTENCY_KEY_REUSED conflict, and would let the
     * next attempt reuse an audit source reference that already exists.
     */
    private fun failAfter(
        commandId: UUID,
        attempt: Int,
        failure: DomainFailure,
    ): Nothing {
        val next =
            if (attempt >= MAX_ATTEMPTS) {
                SearchIndexRebuildCommandState.MANUAL_REVIEW
            } else {
                SearchIndexRebuildCommandState.FAILED_RETRYABLE
            }
        try {
            commands.markFailed(commandId, next, clock.instant())
        } catch (cleanupFailure: RuntimeException) {
            throw outcomeUnknown("Search index rebuild outcome could not be recorded", cleanupFailure)
        }
        throw failure
    }

    private fun markUnknown(commandId: UUID) {
        try {
            commands.markFailed(commandId, SearchIndexRebuildCommandState.UNKNOWN, clock.instant())
        } catch (cleanupFailure: RuntimeException) {
            // The caller already receives "outcome unknown"; losing the marker does not change that
            // answer, but an operator needs the trail to find the command.
            logger.warn("Search index rebuild command {} could not be marked UNKNOWN", commandId, cleanupFailure)
        }
    }

    private fun rebuildFailed(cause: Throwable) =
        DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Search index rebuild could not be completed").also {
            it.initCause(cause)
        }

    private fun outcomeUnknown(
        message: String,
        cause: Throwable,
    ) = DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { it.initCause(cause) }

    private companion object {
        /** Matches the `attempt_count` CHECK in V64. Beyond this the command needs an operator. */
        const val MAX_ATTEMPTS = 5
        val logger: Logger = LoggerFactory.getLogger(OperatorSearchIndexRebuildService::class.java)
    }
}

internal sealed interface SearchIndexRebuildClaim {
    data class Started(
        val commandId: UUID,
        val attempt: Int,
    ) : SearchIndexRebuildClaim

    data class Replay(
        val result: StoreSearchIndexRebuildResult,
    ) : SearchIndexRebuildClaim

    /** Retry attempts are used up. The caller commits the escalation, then fails the request. */
    data class Exhausted(
        val commandId: UUID,
    ) : SearchIndexRebuildClaim
}

internal fun manualReviewRequired() =
    DomainFailure(
        FailureCode.IDEMPOTENCY_MANUAL_REVIEW_REQUIRED,
        "Automatic processing stopped and this rebuild command requires manual review",
    )

/** Short transactions for a long-running, per-store rebuild command. */
@Component
internal class SearchIndexRebuildCommandLedger(
    private val repository: SearchIndexRebuildCommandRepository,
    private val authorization: OperatorPermissionAuthorization,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val auditRecords: AuditRecordOperations,
    private val auditQueries: AuditRecordQueryOperations,
    private val correlationIds: CorrelationIdSource,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
    @Value("\${beanflow.idempotency.retry-after-seconds:2}")
    private val retryAfterSeconds: Long,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(command: OperatorSearchIndexRebuildCommand): SearchIndexRebuildClaim {
        val normalizedReason = validate(command)
        authorization.requireActive(command.actorId, OperatorPermission.STORE_BRAND_MANAGE)
        advisoryLock.lock("search-index-rebuild:${command.actorId}:${command.idempotencyKey}")
        val payloadHash = payloadHash(normalizedReason)
        val existing = repository.find(command.actorId, command.idempotencyKey)
        if (existing != null) {
            if (existing.payloadHash != payloadHash) {
                throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another rebuild request")
            }
            return when (existing.state) {
                SearchIndexRebuildCommandState.COMPLETED -> {
                    SearchIndexRebuildClaim.Replay(
                        objectMapper.readValue(requireNotNull(existing.responseJson), StoreSearchIndexRebuildResult::class.java),
                    )
                }

                SearchIndexRebuildCommandState.RUNNING -> {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "Search index rebuild with this Idempotency-Key is still running",
                        retryAfterSeconds = retryAfterSeconds,
                    )
                }

                // Per-store rebuild replaces a store's terms outright, so repeating a failed pass is
                // safe. The retry stays on this row so the key keeps its payload binding.
                SearchIndexRebuildCommandState.FAILED_RETRYABLE -> {
                    restart(command, normalizedReason, existing)
                }

                // The outcome of these is not known to be safe to repeat automatically. The command
                // is not rerun and no new command is created under the same key.
                SearchIndexRebuildCommandState.UNKNOWN,
                SearchIndexRebuildCommandState.MANUAL_REVIEW,
                -> {
                    throw manualReviewRequired()
                }
            }
        }

        val commandId = identifiers.next()
        repository.insert(commandId, command.actorId, command.idempotencyKey, payloadHash, command.now)
        appendRequestAudit(command, normalizedReason, commandId, FIRST_ATTEMPT)
        return SearchIndexRebuildClaim.Started(commandId, FIRST_ATTEMPT)
    }

    private fun restart(
        command: OperatorSearchIndexRebuildCommand,
        normalizedReason: String,
        existing: SearchIndexRebuildCommandRecord,
    ): SearchIndexRebuildClaim {
        val attempt = existing.attemptCount + 1
        // Escalation is reported rather than written here. Throwing out of this REQUIRES_NEW
        // transaction would roll the state change back with it, leaving the row retryable forever.
        if (attempt > MAX_ATTEMPTS) return SearchIndexRebuildClaim.Exhausted(existing.id)
        repository.restart(existing.id, attempt)
        appendRequestAudit(command, normalizedReason, existing.id, attempt)
        return SearchIndexRebuildClaim.Started(existing.id, attempt)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun escalate(
        commandId: UUID,
        failedAt: Instant,
    ) {
        repository.escalate(commandId, failedAt)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(
        commandId: UUID,
        result: StoreSearchIndexRebuildResult,
        completedAt: Instant,
    ) {
        if (!repository.complete(commandId, objectMapper.writeValueAsString(result), completedAt)) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Search index rebuild result could not be recorded")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(
        commandId: UUID,
        state: SearchIndexRebuildCommandState,
        failedAt: Instant,
    ) {
        repository.markFailed(commandId, state, failedAt)
    }

    /**
     * The reference is per command attempt, not per Idempotency-Key. Keys are reusable once their
     * command row is gone, while AuditRecord is retained far longer, so a key-derived reference
     * would make a later rebuild look like an already-recorded one and skip its audit entirely.
     */
    private fun appendRequestAudit(
        command: OperatorSearchIndexRebuildCommand,
        normalizedReason: String,
        commandId: UUID,
        attempt: Int,
    ) {
        val sourceReference = "search-index-rebuild:$commandId:$attempt"
        val key = AuditRecordKey(AUDIT_ACTION, AUDIT_TARGET_TYPE, AUDIT_TARGET_ID, sourceReference)
        if (auditQueries.exists(key)) return
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = AUDIT_ACTION,
                    targetType = AUDIT_TARGET_TYPE,
                    targetId = AUDIT_TARGET_ID,
                    occurredAt = command.now,
                    reason = normalizedReason,
                    afterSummary =
                        mapOf(
                            "commandState" to SearchIndexRebuildCommandState.RUNNING.name,
                            "attempt" to attempt.toString(),
                        ),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun validate(command: OperatorSearchIndexRebuildCommand): String {
        if (command.idempotencyKey.length !in 8..128 || command.idempotencyKey != command.idempotencyKey.trim() ||
            command.idempotencyKey.any { it.isISOControl() }
        ) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace",
            )
        }
        val reason = command.reason.trim()
        if (reason.length !in 1..MAX_REASON_LENGTH || reason.any { it.isISOControl() }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Reason must be 1 to $MAX_REASON_LENGTH characters without control characters")
        }
        return reason
    }

    private fun payloadHash(reason: String): String = sha256("SEARCH_INDEX_REBUILD\u001F$reason")

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val MAX_REASON_LENGTH = 200
        const val FIRST_ATTEMPT = 1

        /** Matches the `attempt_count` CHECK in V64. */
        const val MAX_ATTEMPTS = 5
        const val AUDIT_ACTION = "STORE_SEARCH_INDEX_REBUILD_REQUESTED"
        const val AUDIT_TARGET_TYPE = "SEARCH_INDEX"
        val AUDIT_TARGET_ID: UUID = UUID.nameUUIDFromBytes("store-search-index".toByteArray(StandardCharsets.UTF_8))
    }
}

/**
 * A failed command keeps its row so that `(actor_id, idempotency_key)` stays bound to the payload
 * it was accepted with. The three failure states differ in whether a repeat is allowed to rerun the
 * work by itself, which is the whole reason they are separate rather than one FAILED.
 */
internal enum class SearchIndexRebuildCommandState {
    RUNNING,
    COMPLETED,

    /** The pass failed and repeating it is safe; a same-payload retry reuses this row. */
    FAILED_RETRYABLE,

    /** The outcome could not be confirmed. Never rerun automatically. */
    UNKNOWN,

    /** Retry attempts are exhausted. Never rerun automatically. */
    MANUAL_REVIEW,
}

internal data class SearchIndexRebuildCommandRecord(
    val id: UUID,
    val payloadHash: String,
    val state: SearchIndexRebuildCommandState,
    val attemptCount: Int,
    val responseJson: String?,
)

@Repository
internal class SearchIndexRebuildCommandRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(
        actorId: UUID,
        idempotencyKey: String,
    ): SearchIndexRebuildCommandRecord? =
        jdbc
            .query(
                """
                SELECT id, payload_hash, state, attempt_count, response_json
                  FROM operations_search_index_rebuild_command
                 WHERE actor_id = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ ->
                    SearchIndexRebuildCommandRecord(
                        id = row.getObject("id", UUID::class.java),
                        payloadHash = row.getString("payload_hash"),
                        state = SearchIndexRebuildCommandState.valueOf(row.getString("state")),
                        attemptCount = row.getInt("attempt_count"),
                        responseJson = row.getString("response_json"),
                    )
                },
                actorId,
                idempotencyKey,
            ).firstOrNull()

    fun insert(
        id: UUID,
        actorId: UUID,
        idempotencyKey: String,
        payloadHash: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO operations_search_index_rebuild_command (
                id, actor_id, idempotency_key, payload_hash, state, created_at, retention_expires_at
            ) VALUES (?, ?, ?, ?, 'RUNNING', ?, CAST(? AS timestamptz) + interval '90 days')
            """.trimIndent(),
            id,
            actorId,
            idempotencyKey,
            payloadHash,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
        )
    }

    fun complete(
        id: UUID,
        responseJson: String,
        completedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE operations_search_index_rebuild_command
               SET state = 'COMPLETED', response_json = ?, completed_at = ?
             WHERE id = ? AND state = 'RUNNING'
            """.trimIndent(),
            responseJson,
            Timestamp.from(completedAt),
            id,
        ) == 1

    /**
     * Conditional on the row still being RUNNING. When the outcome is unknown because a COMPLETED
     * commit may have landed, that committed result must win over the UNKNOWN marker.
     */
    fun markFailed(
        id: UUID,
        state: SearchIndexRebuildCommandState,
        failedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE operations_search_index_rebuild_command
               SET state = ?, last_failure_at = ?
             WHERE id = ? AND state = 'RUNNING'
            """.trimIndent(),
            state.name,
            Timestamp.from(failedAt),
            id,
        ) == 1

    /** Reuses the row so the Idempotency-Key keeps its payload binding across attempts. */
    fun restart(
        id: UUID,
        attemptCount: Int,
    ): Boolean =
        jdbc.update(
            """
            UPDATE operations_search_index_rebuild_command
               SET state = 'RUNNING', attempt_count = ?
             WHERE id = ? AND state = 'FAILED_RETRYABLE'
            """.trimIndent(),
            attemptCount,
            id,
        ) == 1

    fun escalate(
        id: UUID,
        failedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE operations_search_index_rebuild_command
               SET state = 'MANUAL_REVIEW', last_failure_at = ?
             WHERE id = ? AND state = 'FAILED_RETRYABLE'
            """.trimIndent(),
            Timestamp.from(failedAt),
            id,
        ) == 1

    @Transactional
    fun deleteExpiredCompleted(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..MAX_CLEANUP_BATCH_SIZE) { "Search index rebuild command cleanup batch size is invalid" }
        return jdbc
            .queryForObject(
                """
                WITH candidates AS (
                    SELECT id
                      FROM operations_search_index_rebuild_command
                     WHERE state IN ('COMPLETED', 'FAILED_RETRYABLE') AND retention_expires_at <= ?
                     ORDER BY retention_expires_at ASC, id ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM operations_search_index_rebuild_command command
                     USING candidates
                     WHERE command.id = candidates.id
                    RETURNING command.id
                )
                SELECT count(*) FROM deleted
                """.trimIndent(),
                Long::class.java,
                Timestamp.from(now),
                batchSize,
            )!!
            .toInt()
    }

    private companion object {
        const val MAX_CLEANUP_BATCH_SIZE = 1_000
    }
}

@Component
internal class SearchIndexRebuildCommandRetentionWorker(
    private val repository: SearchIndexRebuildCommandRepository,
    private val clock: Clock,
    @Value("\${beanflow.search-index-rebuild-command-retention.batch-size:100}")
    private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.search-index-rebuild-command-retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.search-index-rebuild-command-retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        repository.deleteExpiredCompleted(clock.instant(), batchSize)
    }
}
