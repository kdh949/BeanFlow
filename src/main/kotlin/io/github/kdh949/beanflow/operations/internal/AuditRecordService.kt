package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.RetentionClass
import io.github.kdh949.beanflow.operations.api.RetentionDurationBasis
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import io.github.kdh949.beanflow.operations.api.RetentionPolicyVersionSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

internal data class AuditRetentionPurgeResult(
    val deletedCount: Int,
    val oldestDueAt: Instant?,
)

@Service
internal class AuditRecordService(
    private val repository: AuditRecordJpaRepository,
    private val retentionPolicies: RetentionPolicyOperations,
    private val identifierSource: IdentifierSource,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager,
) : AuditRecordOperations,
    AuditRecordQueryOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendAll(commands: List<AppendAuditRecordCommand>): List<UUID> {
        if (commands.isEmpty()) return emptyList()
        val policies =
            commands
                .map(AppendAuditRecordCommand::category)
                .distinct()
                .associateWith { category ->
                    retentionPolicies.current(RetentionPolicyCategory.valueOf(category.name))
                }
        val records =
            commands.map { command ->
                validate(command)
                val policy = requireNotNull(policies[command.category])
                validateAuditPolicy(command.category, policy)
                AuditRecordEntity(
                    id = identifierSource.next(),
                    actorId = command.actorId,
                    actorType = command.actorType,
                    category = command.category,
                    action = command.action,
                    targetType = command.targetType,
                    targetId = command.targetId,
                    occurredAt = command.occurredAt,
                    reason = command.reason,
                    beforeSummary = objectMapper.writeValueAsString(command.beforeSummary),
                    afterSummary = objectMapper.writeValueAsString(command.afterSummary),
                    correlationId = command.correlationId,
                    sourceReference = command.sourceReference,
                    retentionExpiresAt = retentionExpiry(command.occurredAt, policy),
                    retentionClass = policy.retentionClass,
                    retentionPolicyVersionId = policy.policyVersionId,
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

    private fun validateAuditPolicy(
        category: AuditCategory,
        policy: RetentionPolicyVersionSnapshot,
    ) {
        val expectedClass =
            if (category == AuditCategory.PII_ACCESS) {
                RetentionClass.PII_ACCESS_AUDIT
            } else {
                RetentionClass.FINANCIAL_AUDIT
            }
        if (policy.category.name != category.name ||
            policy.retentionClass != expectedClass ||
            policy.durationBasis != RetentionDurationBasis.SEOUL_CALENDAR_YEARS
        ) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Audit retention policy shape is invalid")
        }
    }

    internal fun retentionExpiry(
        occurredAt: Instant,
        policy: RetentionPolicyVersionSnapshot,
    ): Instant =
        try {
            occurredAt.atZone(SEOUL).plusYears(policy.durationValue.toLong()).toInstant()
        } catch (failure: RuntimeException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Audit retention expiry could not be computed",
            ).also { it.initCause(failure) }
        }

    @Transactional
    fun purgeDue(
        now: Instant,
        chunkSize: Int,
    ): AuditRetentionPurgeResult {
        require(chunkSize > 0)
        val row =
            entityManager
                .createNativeQuery(PURGE_DUE_SQL)
                .setParameter("now", now)
                .setParameter("chunkSize", chunkSize)
                .singleResult as Array<*>
        return AuditRetentionPurgeResult(
            deletedCount = (row[0] as Number).toInt(),
            oldestDueAt = row[1]?.toInstant(),
        )
    }

    private fun Any.toInstant(): Instant =
        when (this) {
            is Instant -> this
            is OffsetDateTime -> toInstant()
            is Timestamp -> toInstant()
            else -> error("Unsupported PostgreSQL timestamp value: ${this::class.qualifiedName}")
        }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val SENSITIVE_KEY_PARTS =
            setOf(
                "password",
                "secret",
                "token",
                "cardNumber",
                "cvc",
                "rawPayload",
                "email",
                "phone",
                "address",
                "fullName",
                "customerName",
                "recipientName",
                "birth",
                "pii",
            )
        const val PURGE_DUE_SQL =
            """
            WITH due AS (
                SELECT id
                  FROM operations_audit_record
                 WHERE retention_expires_at <= :now
                 ORDER BY retention_expires_at, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :chunkSize
            ), deleted AS (
                DELETE FROM operations_audit_record record
                 USING due
                 WHERE record.id = due.id
                 RETURNING record.retention_expires_at
            )
            SELECT count(*)::bigint, min(retention_expires_at)
              FROM deleted
            """
    }
}
