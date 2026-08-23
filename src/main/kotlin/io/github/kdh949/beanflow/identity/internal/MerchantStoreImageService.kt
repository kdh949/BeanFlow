package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
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
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class MerchantStoreImageService(
    private val transactions: MerchantStoreImageTransaction,
    private val images: StoreImageOperations,
    private val storage: StorefrontImageStorageOperations,
    private val clock: Clock,
) {
    fun replace(
        actorId: UUID,
        storeId: UUID,
        upload: StorefrontImageUpload,
    ): StorefrontImageAccess {
        transactions.authorize(actorId, storeId)
        val current = images.find(storeId)
        val normalized = storage.normalize(upload)
        if (current?.sha256 == normalized.sha256) return storage.access(current.thumbnailKey)

        val prepared = storage.store(StorefrontImageTarget.STORE, storeId, normalized)
        // Signing is local and happens before the pointer commit, so an impossible signing result
        // cannot turn an already-committed write into an ambiguous 503 response.
        val access = storage.access(prepared.thumbnailKey)
        transactions.replace(actorId, storeId, prepared, clock.instant())
        return access
    }

    fun delete(
        actorId: UUID,
        storeId: UUID,
    ) {
        transactions.clear(actorId, storeId, clock.instant())
    }
}

@Component
internal class MerchantStoreImageTransaction(
    private val storeAccess: StoreAccessOperations,
    private val images: StoreImageOperations,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
) {
    @Transactional
    fun authorize(
        actorId: UUID,
        storeId: UUID,
    ) {
        storeAccess.requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
    }

    @Transactional
    fun replace(
        actorId: UUID,
        storeId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): StoreImageChange {
        storeAccess.requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
        val change = images.replace(storeId, prepared, now)
        if (change.changed) audit(actorId, storeId, "STORE_IMAGE_UPDATED", change, now)
        return change
    }

    @Transactional
    fun clear(
        actorId: UUID,
        storeId: UUID,
        now: Instant,
    ) {
        storeAccess.requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
        val change = images.clear(storeId, now)
        if (change.changed) audit(actorId, storeId, "STORE_IMAGE_DELETED", change, now)
    }

    private fun audit(
        actorId: UUID,
        storeId: UUID,
        action: String,
        change: StoreImageChange,
        now: Instant,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.STORE_OWNER,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = "Store",
                    targetId = storeId,
                    occurredAt = now,
                    reason = "MERCHANT_STORE_IMAGE_CHANGE",
                    beforeSummary = imageState(change.previous != null),
                    afterSummary = imageState(change.current != null),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "store-image:$storeId:${change.previous?.sha256.orEmpty()}:${change.current?.sha256.orEmpty()}:$now",
                ),
            ),
        )
    }

    private fun imageState(present: Boolean) = mapOf("imageState" to if (present) "PRESENT" else "ABSENT")
}
