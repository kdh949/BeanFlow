package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.AssignStoreRegionCommand
import io.github.kdh949.beanflow.merchant.api.StoreRegionAssignment
import io.github.kdh949.beanflow.merchant.api.StoreRegionOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

internal data class StoreRegionCommandContext(
    val actorId: UUID,
    val idempotencyKey: String,
    val reason: String,
)

/**
 * The store owner side of ADR-112: store membership, audit and the transaction the region write
 * runs in.
 *
 * The command lives in `identity` because it is the only module that already reaches everything it
 * needs — store membership is `identity`'s own, and `identity` already depends on `merchant` for
 * data and on `operations` for audit. Putting it in `operations` instead would need
 * `operations` to depend on `identity`, which `identity` already depends on, and Spring Modulith
 * would reject the cycle.
 *
 * `STORE_OWNER` alone can run it (ADR-112 4절). A store's region is store identity, not day-to-day
 * operation, so `STORE_STAFF` is denied here rather than in a later check.
 */
@Service
internal class StoreRegionCommandService(
    private val storeAccess: StoreAccessOperations,
    private val regions: StoreRegionOperations,
    private val auditRecords: AuditRecordOperations,
    private val auditRecordQueries: AuditRecordQueryOperations,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun assign(
        context: StoreRegionCommandContext,
        storeId: UUID,
        regionCode: String,
    ): StoreRegionAssignment {
        validateReason(context.reason)
        // 타 매장 소유자에게는 membership 자체가 없고 STORE_STAFF에게는 역할이 맞지 않는다.
        // 둘 다 ACCESS_DENIED 403이며 지역 행을 읽기 전에 막힌다.
        storeAccess.requireStoreAccess(context.actorId, storeId, setOf(StoreActorRole.OWNER))
        val assignment =
            regions.assignStoreRegion(
                AssignStoreRegionCommand(
                    actorId = context.actorId,
                    idempotencyKey = context.idempotencyKey,
                    storeId = storeId,
                    regionCode = regionCode,
                    now = clock.instant(),
                ),
            )
        audit(context, storeId, assignment)
        return assignment
    }

    /**
     * Appends the audit entry unless this exact command already produced one.
     *
     * A replayed command returns the stored result without changing anything, so a second record
     * would claim a change that did not happen. The check uses the source reference the first
     * attempt wrote, which is derived from the idempotency key.
     */
    private fun audit(
        context: StoreRegionCommandContext,
        storeId: UUID,
        assignment: StoreRegionAssignment,
    ) {
        val sourceReference = "store-region-command:${context.actorId}:${sha256(context.idempotencyKey)}"
        if (auditRecordQueries.exists(AuditRecordKey(ACTION, TARGET_TYPE, storeId, sourceReference))) return
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = context.actorId.toString(),
                    actorType = AuditActorType.STORE_OWNER,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = ACTION,
                    targetType = TARGET_TYPE,
                    targetId = storeId,
                    occurredAt = clock.instant(),
                    reason = context.reason.trim(),
                    // 표시 이름은 담지 않는다. 민감 키 규칙이 'fullName'을 포함하는 이름을
                    // 거절하며, 법정동 이름은 코드로부터 폐쇄 어휘를 통해 언제든 되찾을 수 있다.
                    beforeSummary = mapOf("regionCode" to groupedRegionCode(assignment.previousRegionCode)),
                    afterSummary = mapOf("regionCode" to groupedRegionCode(assignment.region.code)),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    /**
     * The 법정동 code in its own 시도(2)·시군구(3)·읍면동(3)·리(2) grouping.
     *
     * A bare 10-digit code matches the raw-PII guard's Korean mobile-number pattern, so an audit
     * append carrying `1168010100` is rejected outright. The grouping is the code's actual
     * structure rather than an invented format, and dropping the hyphens gives the stored value
     * back unchanged.
     */
    private fun groupedRegionCode(code: String?): String {
        if (code.isNullOrBlank()) return ""
        if (code.length != REGION_CODE_LENGTH) return code
        return listOf(code.substring(0, 2), code.substring(2, 5), code.substring(5, 8), code.substring(8, 10))
            .joinToString("-")
    }

    private fun validateReason(reason: String) {
        if (reason.trim().length !in 1..MAX_REASON_LENGTH || reason.any { it.isISOControl() }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Reason must be 1 to $MAX_REASON_LENGTH characters")
        }
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val MAX_REASON_LENGTH = 200
        const val ACTION = "STORE_REGION_ASSIGNED"
        const val TARGET_TYPE = "Store"
        const val REGION_CODE_LENGTH = 10
    }
}
