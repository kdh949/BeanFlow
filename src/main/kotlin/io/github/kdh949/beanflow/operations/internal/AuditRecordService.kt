package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.AuditRetentionProvenance
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
                    retentionProvenance = AuditRetentionProvenance.APPEND_SNAPSHOT,
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
        val values = command.beforeSummary.values + command.afterSummary.values + command.reason
        if (values.any(::containsRawPii)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Audit payload contains raw PII")
        }
    }

    private fun containsRawPii(value: String): Boolean {
        if (value.isUuid()) return false
        return RAW_PII_PATTERNS.any { it.containsMatchIn(value) } || containsPaymentCardNumber(value)
    }

    private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this).toString() == lowercase() }.getOrDefault(false)

    private fun containsPaymentCardNumber(value: String): Boolean {
        if (value.any { !it.isDigit() && it != ' ' && it != '-' }) return false
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        return digits
            .reversed()
            .mapIndexed { index, digit ->
                val numeric = digit.digitToInt()
                if (index % 2 == 0) numeric else (numeric * 2).let { if (it > 9) it - 9 else it }
            }.sum() % 10 == 0
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
        val RAW_PII_PATTERNS =
            listOf(
                Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
                Regex("""(?<!\d)(?:\+?82[-\s]?)?0?1[0-9][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)"""),
                Regex(
                    """(?:서울(?:특별시)?|부산(?:광역시)?|대구(?:광역시)?|인천(?:광역시)?|광주(?:광역시)?|대전(?:광역시)?|울산(?:광역시)?|세종(?:특별자치시)?|경기도|강원(?:특별자치도)?|충청[남북]도|전라[남북]도|경상[남북]도|제주(?:특별자치도)?)[^\n]{0,80}?(?:[가-힣A-Za-z]+(?:로|길)\s*\d+|\d+(?:번지|호)?)""",
                ),
                Regex(
                    """\b\d{1,6}\s+[A-Za-z][A-Za-z .'-]{1,50}\s+(?:street|st\.?|road|rd\.?|avenue|ave\.?|lane|ln\.?|drive|dr\.?|boulevard|blvd\.?)\b""",
                    RegexOption.IGNORE_CASE,
                ),
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
