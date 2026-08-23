package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreImageChange
import io.github.kdh949.beanflow.merchant.api.StoreImageOperations
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
internal class OperatorStoreImageService(
    private val transactions: OperatorStoreImageTransaction,
    private val images: StoreImageOperations,
    private val storage: StorefrontImageStorageOperations,
    private val clock: Clock,
) {
    fun replace(
        actorId: UUID,
        storeId: UUID,
        reason: String,
        upload: StorefrontImageUpload,
    ): StorefrontImageAccess {
        transactions.authorize(actorId, reason)
        val current = images.find(storeId)
        val normalized = storage.normalize(upload)
        if (current?.sha256 == normalized.sha256) return storage.access(current.thumbnailKey)
        val prepared = storage.store(StorefrontImageTarget.STORE, storeId, normalized)
        val access = storage.access(prepared.thumbnailKey)
        transactions.replace(actorId, storeId, reason, prepared, clock.instant())
        return access
    }

    fun delete(
        actorId: UUID,
        storeId: UUID,
        reason: String,
    ) {
        transactions.clear(actorId, storeId, reason, clock.instant())
    }
}

@Component
internal class OperatorStoreImageTransaction(
    private val authorization: OperatorPermissionAuthorization,
    private val images: StoreImageOperations,
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
        reason: String,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): StoreImageChange {
        authorize(actorId, reason)
        val change = images.replace(storeId, prepared, now)
        if (change.changed) audit(actorId, storeId, reason, "STORE_IMAGE_UPDATED", change, now)
        return change
    }

    @Transactional
    fun clear(
        actorId: UUID,
        storeId: UUID,
        reason: String,
        now: Instant,
    ) {
        authorize(actorId, reason)
        val change = images.clear(storeId, now)
        if (change.changed) audit(actorId, storeId, reason, "STORE_IMAGE_DELETED", change, now)
    }

    private fun audit(
        actorId: UUID,
        storeId: UUID,
        reason: String,
        action: String,
        change: StoreImageChange,
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
                    targetType = "Store",
                    targetId = storeId,
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
