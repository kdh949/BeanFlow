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
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
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
    fun rebuild(command: OperatorSearchIndexRebuildCommand): StoreSearchIndexRebuildResult =
        when (val claim = commands.claim(command)) {
            is SearchIndexRebuildClaim.Replay -> claim.result
            is SearchIndexRebuildClaim.Started -> rebuildClaimed(command, claim.commandId)
        }

    private fun rebuildClaimed(
        command: OperatorSearchIndexRebuildCommand,
        commandId: UUID,
    ): StoreSearchIndexRebuildResult {
        val result =
            try {
                rebuilds.rebuildAll()
            } catch (failure: DomainFailure) {
                abandonAfterFailure(commandId, failure)
            } catch (failure: DataAccessException) {
                abandonAfterFailure(
                    commandId,
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Search index rebuild could not be completed").also {
                        it.initCause(failure)
                    },
                )
            } catch (failure: RuntimeException) {
                abandonAfterFailure(
                    commandId,
                    DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Search index rebuild could not be completed").also {
                        it.initCause(failure)
                    },
                )
            }
        commands.complete(commandId, result, clock.instant())
        return result
    }

    private fun abandonAfterFailure(
        commandId: UUID,
        failure: DomainFailure,
    ): Nothing {
        try {
            commands.abandon(commandId)
        } catch (cleanupFailure: RuntimeException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Search index rebuild outcome could not be recorded",
            ).also { it.initCause(cleanupFailure) }
        }
        throw failure
    }
}

internal sealed interface SearchIndexRebuildClaim {
    data class Started(
        val commandId: UUID,
    ) : SearchIndexRebuildClaim

    data class Replay(
        val result: StoreSearchIndexRebuildResult,
    ) : SearchIndexRebuildClaim
}

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
            }
        }

        val commandId = identifiers.next()
        repository.insert(commandId, command.actorId, command.idempotencyKey, payloadHash, command.now)
        appendRequestAudit(command, normalizedReason)
        return SearchIndexRebuildClaim.Started(commandId)
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
    fun abandon(commandId: UUID) {
        repository.deleteRunning(commandId)
    }

    private fun appendRequestAudit(
        command: OperatorSearchIndexRebuildCommand,
        normalizedReason: String,
    ) {
        val sourceReference = "search-index-rebuild:${command.actorId}:${sha256(command.idempotencyKey)}"
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
                    afterSummary = mapOf("commandState" to SearchIndexRebuildCommandState.RUNNING.name),
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
        const val AUDIT_ACTION = "STORE_SEARCH_INDEX_REBUILD_REQUESTED"
        const val AUDIT_TARGET_TYPE = "SEARCH_INDEX"
        val AUDIT_TARGET_ID: UUID = UUID.nameUUIDFromBytes("store-search-index".toByteArray(StandardCharsets.UTF_8))
    }
}

internal enum class SearchIndexRebuildCommandState {
    RUNNING,
    COMPLETED,
}

internal data class SearchIndexRebuildCommandRecord(
    val id: UUID,
    val payloadHash: String,
    val state: SearchIndexRebuildCommandState,
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
                SELECT id, payload_hash, state, response_json
                  FROM operations_search_index_rebuild_command
                 WHERE actor_id = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ ->
                    SearchIndexRebuildCommandRecord(
                        id = row.getObject("id", UUID::class.java),
                        payloadHash = row.getString("payload_hash"),
                        state = SearchIndexRebuildCommandState.valueOf(row.getString("state")),
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

    fun deleteRunning(id: UUID) {
        jdbc.update("DELETE FROM operations_search_index_rebuild_command WHERE id = ? AND state = 'RUNNING'", id)
    }

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
                     WHERE state = 'COMPLETED' AND retention_expires_at <= ?
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
