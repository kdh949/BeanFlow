package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.AssignStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.BrandSnapshot
import io.github.kdh949.beanflow.merchant.api.BrandStatus
import io.github.kdh949.beanflow.merchant.api.ClearStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.CreateBrandCommand
import io.github.kdh949.beanflow.merchant.api.StoreBrandAssignment
import io.github.kdh949.beanflow.merchant.api.StoreBrandOperations
import io.github.kdh949.beanflow.merchant.api.StoreBrandQueryOperations
import io.github.kdh949.beanflow.merchant.api.UpdateBrandCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordKey
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordQueryOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

internal data class OperatorBrandCommandContext(
    val actorId: UUID,
    val idempotencyKey: String,
    val reason: String,
)

/**
 * The operator side of ADR-112: permission, audit and the transaction the brand write runs in.
 *
 * This service opens the transaction that everything else joins — the brand row, the affected
 * stores' search terms, the replay ledger and the AuditRecord. `merchant` owns brand data and
 * declares its write port `MANDATORY`, so a brand command that skipped this boundary would fail
 * rather than commit half of itself.
 *
 * Brand data is not personal data, so reads need the grant but no access reason. Commands need a
 * reason because they change what customers see when they search.
 */
@Service
internal class OperatorBrandService(
    private val brands: StoreBrandOperations,
    private val brandQueries: StoreBrandQueryOperations,
    private val authorization: OperatorPermissionAuthorization,
    private val auditRecords: AuditRecordOperations,
    private val auditRecordQueries: AuditRecordQueryOperations,
    private val correlationIds: CorrelationIdSource,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        context: OperatorBrandCommandContext,
        name: String,
    ): BrandSnapshot {
        authorize(context)
        val brand = brands.create(CreateBrandCommand(context.actorId, context.idempotencyKey, name, clock.instant()))
        audit(
            context,
            action = "BRAND_CREATED",
            targetType = "Brand",
            targetId = brand.brandId,
            before = emptyMap(),
            after = brand.summary(),
        )
        return brand
    }

    @Transactional
    fun update(
        context: OperatorBrandCommandContext,
        brandId: UUID,
        name: String?,
        status: BrandStatus?,
        expectedVersion: Long?,
    ): BrandSnapshot {
        authorize(context)
        val before = brandQueries.find(brandId)
        val brand =
            brands.update(
                UpdateBrandCommand(context.actorId, context.idempotencyKey, brandId, name, status, expectedVersion, clock.instant()),
            )
        audit(
            context,
            action = "BRAND_UPDATED",
            targetType = "Brand",
            targetId = brand.brandId,
            before = before?.summary() ?: emptyMap(),
            after = brand.summary(),
        )
        return brand
    }

    @Transactional
    fun assign(
        context: OperatorBrandCommandContext,
        storeId: UUID,
        brandId: UUID,
    ): StoreBrandAssignment {
        authorize(context)
        val assignment =
            brands.assignStoreBrand(
                AssignStoreBrandCommand(context.actorId, context.idempotencyKey, storeId, brandId, clock.instant()),
            )
        audit(
            context,
            action = "STORE_BRAND_ASSIGNED",
            targetType = "Store",
            targetId = storeId,
            before = emptyMap(),
            after = mapOf("brandId" to brandId.toString()),
        )
        return assignment
    }

    @Transactional
    fun clear(
        context: OperatorBrandCommandContext,
        storeId: UUID,
    ): StoreBrandAssignment {
        authorize(context)
        val assignment = brands.clearStoreBrand(ClearStoreBrandCommand(context.actorId, context.idempotencyKey, storeId, clock.instant()))
        audit(
            context,
            action = "STORE_BRAND_CLEARED",
            targetType = "Store",
            targetId = storeId,
            before = emptyMap(),
            after = mapOf("brandId" to ""),
        )
        return assignment
    }

    // requireActive는 grant 행을 잠그므로 read-only transaction에서는 실행할 수 없다.
    // 권한 확인이 조회의 일부인 이상 읽기 경로도 쓰기 가능한 transaction에서 돈다.
    @Transactional
    fun find(
        actorId: UUID,
        brandId: UUID,
    ): BrandSnapshot {
        authorization.requireActive(actorId, OperatorPermission.STORE_BRAND_MANAGE)
        return brandQueries.find(brandId)
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Brand not found", targetReference = brandId.toString())
    }

    @Transactional
    fun list(
        actorId: UUID,
        cursor: String?,
        limit: Int?,
    ): OperatorBrandPage {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "limit must be between 1 and $MAX_PAGE_SIZE")
        }
        authorization.requireActive(actorId, OperatorPermission.STORE_BRAND_MANAGE)
        val after = cursor?.let { cursors.verify(it, cursorScope()).sort }
        val page = brandQueries.list(after?.normalizedName, after?.brandId, pageSize)
        val nextNormalizedName = page.nextNormalizedName
        val nextBrandId = page.nextBrandId
        val nextCursor =
            if (nextNormalizedName != null && nextBrandId != null) {
                cursors.issue(cursorScope(), BrandSort(nextNormalizedName, nextBrandId), clock.instant().plus(CURSOR_TTL))
            } else {
                null
            }
        return OperatorBrandPage(page.brands, nextCursor)
    }

    /**
     * The brand list has no filters, so the digest is the endpoint name itself. It still binds the
     * cursor to this endpoint, which is what stops another list's cursor from being replayed here.
     */
    private fun cursorScope(): SignedCursorScope<BrandSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256(CURSOR_ENDPOINT),
            sortAdapter =
                object : CursorSortAdapter<BrandSort> {
                    override fun encode(sort: BrandSort): List<String> = listOf(sort.normalizedName, sort.brandId.toString())

                    override fun decode(values: List<String>): BrandSort? {
                        if (values.size != 2) return null
                        return try {
                            BrandSort(values[0], UUID.fromString(values[1]))
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                },
        )

    private fun authorize(context: OperatorBrandCommandContext) {
        if (context.reason.trim().length !in 1..MAX_REASON_LENGTH || context.reason.any { it.isISOControl() }) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Reason must be 1 to $MAX_REASON_LENGTH characters")
        }
        authorization.requireActive(context.actorId, OperatorPermission.STORE_BRAND_MANAGE)
    }

    /**
     * Appends the audit entry unless this exact command already produced one.
     *
     * A replayed command returns the stored result without changing anything, so appending a
     * second record would claim a change that did not happen. The existence check uses the same
     * source reference the first attempt wrote, which is derived from the idempotency key.
     */
    private fun audit(
        context: OperatorBrandCommandContext,
        action: String,
        targetType: String,
        targetId: UUID,
        before: Map<String, String>,
        after: Map<String, String>,
    ) {
        val sourceReference = "brand-command:${context.actorId}:${sha256(context.idempotencyKey)}"
        if (auditRecordQueries.exists(AuditRecordKey(action, targetType, targetId, sourceReference))) return
        auditRecords.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = context.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    occurredAt = clock.instant(),
                    reason = context.reason.trim(),
                    beforeSummary = before,
                    afterSummary = after,
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = sourceReference,
                ),
            ),
        )
    }

    private fun BrandSnapshot.summary(): Map<String, String> =
        mapOf(
            "brandName" to name,
            "status" to status.name,
            "assignedStoreCount" to assignedStoreCount.toString(),
        )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val MAX_REASON_LENGTH = 200
        const val CURSOR_ENDPOINT = "operations-brands"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        val CURSOR_TTL: Duration = Duration.ofMinutes(30)
    }
}

internal data class OperatorBrandPage(
    val brands: List<BrandSnapshot>,
    val nextCursor: String?,
)

internal data class BrandSort(
    val normalizedName: String,
    val brandId: UUID,
)
