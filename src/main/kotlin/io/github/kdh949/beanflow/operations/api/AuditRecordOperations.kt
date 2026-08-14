package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class AuditActorType {
    CUSTOMER,
    MERCHANT,
    STORE_OWNER,
    STORE_STAFF,
    PLATFORM_OPERATOR,
    SYSTEM,
}

enum class AuditCategory {
    FINANCIAL_TRANSACTION,
    ORDER_AND_FULFILLMENT,
    SETTLEMENT_AND_DISPUTE,
    SECURITY_AND_PERMISSION,
    OPERATIONS_POLICY,
    PII_ACCESS,
}

enum class RetentionPolicyCategory {
    FINANCIAL_TRANSACTION,
    ORDER_AND_FULFILLMENT,
    SETTLEMENT_AND_DISPUTE,
    SECURITY_AND_PERMISSION,
    OPERATIONS_POLICY,
    PII_ACCESS,
    SUPPORT_CASE,
    DELIVERY_CONTACT,
    CURRENT_LOCATION,
    PROVIDER_RAW_WEBHOOK,
}

enum class RetentionClass {
    FINANCIAL_AUDIT,
    SUPPORT_CASE,
    PII_ACCESS_AUDIT,
    DELIVERY_CONTACT,
    CURRENT_LOCATION,
    PROVIDER_RAW_WEBHOOK,
}

enum class RetentionDurationBasis {
    SEOUL_CALENDAR_YEARS,
    SEOUL_CALENDAR_YEARS_FROM_CASE_CLOSE,
    EXACT_DAYS_FROM_TERMINAL,
    EXACT_HOURS_FROM_EVENT,
    EXACT_DAYS_FROM_RECEIPT,
    PRESERVE_STORED_EXPIRY,
}

/** Records whether the retention policy is an append-time snapshot or migration/compatibility classification. */
enum class AuditRetentionProvenance {
    APPEND_SNAPSHOT,
    LEGACY_MIGRATION_CLASSIFICATION,
    DATABASE_COMPATIBILITY_SNAPSHOT,
}

data class RetentionPolicyVersionSnapshot(
    val policyVersionId: Long,
    val category: RetentionPolicyCategory,
    val retentionClass: RetentionClass,
    val durationBasis: RetentionDurationBasis,
    val durationValue: Int,
)

interface RetentionPolicyOperations {
    /** Reads and locks the current immutable version in the caller's local transaction. */
    fun current(category: RetentionPolicyCategory): RetentionPolicyVersionSnapshot
}

data class AppendAuditRecordCommand(
    val actorId: String,
    val actorType: AuditActorType,
    val category: AuditCategory,
    val action: String,
    val targetType: String,
    val targetId: UUID,
    val occurredAt: Instant,
    val reason: String,
    val beforeSummary: Map<String, String> = emptyMap(),
    val afterSummary: Map<String, String> = emptyMap(),
    val correlationId: String,
    val sourceReference: String,
)

interface AuditRecordOperations {
    fun appendAll(commands: List<AppendAuditRecordCommand>): List<UUID>
}

data class AuditRecordKey(
    val action: String,
    val targetType: String,
    val targetId: UUID,
    val sourceReference: String,
)

interface AuditRecordQueryOperations {
    fun exists(key: AuditRecordKey): Boolean
}
