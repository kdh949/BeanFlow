package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

enum class AuditActorType {
    CUSTOMER,
    STORE_OWNER,
    STORE_STAFF,
    PLATFORM_OPERATOR,
    SYSTEM,
}

data class AppendAuditRecordCommand(
    val actorId: String,
    val actorType: AuditActorType,
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
