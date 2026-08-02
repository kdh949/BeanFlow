package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class AuditRetentionPurgeResult(
    val deletedCount: Int,
    val oldestDueAt: Instant?,
)

@Service
internal class AuditRecordService(
    private val repository: AuditRecordJpaRepository,
    private val identifierSource: IdentifierSource,
    private val objectMapper: ObjectMapper,
) : AuditRecordOperations, AuditRecordQueryOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendAll(commands: List<AppendAuditRecordCommand>): List<UUID> {
        if (commands.isEmpty()) return emptyList()
        val records =
            commands.map { command ->
                validate(command)
                AuditRecordEntity(
                    id = identifierSource.next(),
                    actorId = command.actorId,
                    actorType = command.actorType,
                    action = command.action,
                    targetType = command.targetType,
                    targetId = command.targetId,
                    occurredAt = command.occurredAt,
                    reason = command.reason,
                    beforeSummary = objectMapper.writeValueAsString(command.beforeSummary),
                    afterSummary = objectMapper.writeValueAsString(command.afterSummary),
                    correlationId = command.correlationId,
                    sourceReference = command.sourceReference,
                    retentionExpiresAt = retentionExpiry(command.occurredAt),
                )
            }
        repository.saveAllAndFlush(records)
        return records.map(AuditRecordEntity::id)
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun exists(key: AuditRecordKey): Boolean =
        repository.existsByActionAndTargetTypeAndTargetIdAndSourceReference(
            key.action,
            key.targetType,
            key.targetId,
            key.sourceReference,
        )

    private fun validate(command: AppendAuditRecordCommand) {
        if (command.actorId.isBlank() || command.action.isBlank() || command.targetType.isBlank() ||
            command.reason.isBlank() || command.correlationId.isBlank() || command.sourceReference.isBlank()
        ) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Audit record required fields must not be blank")
        }
        val keys = command.beforeSummary.keys + command.afterSummary.keys
        if (keys.any { key -> SENSITIVE_KEY_PARTS.any { key.contains(it, ignoreCase = true) } }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Audit summary contains a sensitive field")
        }
    }

    internal fun retentionExpiry(occurredAt: Instant): Instant = occurredAt.atZone(SEOUL).plusYears(5).toInstant()

    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): AuditRetentionPurgeResult {
        require(chunkSize > 0)
        val ids = repository.findDueIds(now, PageRequest.of(0, chunkSize))
        if (ids.isEmpty()) return AuditRetentionPurgeResult(0, null)
        val oldestDueAt = repository.findAllById(ids).minOf(AuditRecordEntity::retentionExpiresAt)
        repository.deleteAllByIdInBatch(ids)
        return AuditRetentionPurgeResult(ids.size, oldestDueAt)
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val SENSITIVE_KEY_PARTS = setOf("password", "secret", "token", "cardNumber", "cvc", "rawPayload")
    }
}
