package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.MenuImageChange
import io.github.kdh949.beanflow.merchant.api.MenuImageOperations
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class OperatorMenuImageService(
    private val transactions: OperatorMenuImageTransaction,
    private val images: MenuImageOperations,
    private val storage: StorefrontImageStorageOperations,
    private val clock: Clock,
) {
    fun replace(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        reason: String,
        upload: StorefrontImageUpload,
    ): StorefrontImageAccess {
        transactions.authorize(actorId, reason)
        val current = images.find(storeId, menuId)
        val normalized = storage.normalize(upload)
        if (current?.sha256 == normalized.sha256) return storage.access(current.thumbnailKey)
        val prepared = storage.store(StorefrontImageTarget.MENU, menuId, normalized)
        val access = storage.access(prepared.thumbnailKey)
        transactions.replace(actorId, storeId, menuId, reason, prepared, clock.instant())
        return access
    }

    fun delete(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        reason: String,
    ) {
        transactions.clear(actorId, storeId, menuId, reason, clock.instant())
    }
}

@Component
internal class OperatorMenuImageTransaction(
    private val authorization: OperatorPermissionAuthorization,
    private val images: MenuImageOperations,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
) {
    @Transactional
    fun authorize(
        actorId: UUID,
        reason: String,
    ) {
        validReason(reason)
        authorization.requireActive(actorId, OperatorPermission.STORE_MEDIA_MANAGE)
    }

    @Transactional
    fun replace(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        reason: String,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): MenuImageChange {
        authorize(actorId, reason)
        val change = images.replace(storeId, menuId, prepared, now)
        if (change.changed) audit(actorId, menuId, reason, "MENU_IMAGE_UPDATED", change, now)
        return change
    }

    @Transactional
    fun clear(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        reason: String,
        now: Instant,
    ) {
        authorize(actorId, reason)
        val change = images.clear(storeId, menuId, now)
        if (change.changed) audit(actorId, menuId, reason, "MENU_IMAGE_DELETED", change, now)
    }

    private fun audit(
        actorId: UUID,
        menuId: UUID,
        reason: String,
        action: String,
        change: MenuImageChange,
        now: Instant,
    ) {
        val correlationId = correlationIds.currentOrCreate()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = "Menu",
                    targetId = menuId,
                    occurredAt = now,
                    reason = reason.trim(),
                    beforeSummary = imageState(change.previous != null),
                    afterSummary = imageState(change.current != null),
                    correlationId = correlationId,
                    sourceReference = "$action:$correlationId",
                ),
            ),
        )
    }

    private fun imageState(present: Boolean) = mapOf("imageState" to if (present) "PRESENT" else "ABSENT")

    private fun validReason(reason: String) {
        if (reason.trim().length !in 1..MAX_REASON_LENGTH || reason.any(Char::isISOControl)) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "X-Access-Reason must be 1 to $MAX_REASON_LENGTH characters")
        }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 200
    }
}
