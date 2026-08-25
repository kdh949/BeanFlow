package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
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
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class MerchantMenuImageService(
    private val transactions: MerchantMenuImageTransaction,
    private val images: MenuImageOperations,
    private val storage: StorefrontImageStorageOperations,
    private val clock: Clock,
) {
    fun replace(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        upload: StorefrontImageUpload,
    ): StorefrontImageAccess {
        transactions.authorize(actorId, storeId)
        val current = images.find(storeId, menuId)
        val normalized = storage.normalize(upload)
        if (current?.sha256 == normalized.sha256) return storage.access(current.thumbnailKey)
        val prepared = storage.store(StorefrontImageTarget.MENU, menuId, normalized)
        val access = storage.access(prepared.thumbnailKey)
        transactions.replace(actorId, storeId, menuId, prepared, clock.instant())
        return access
    }

    fun delete(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
    ) {
        transactions.clear(actorId, storeId, menuId, clock.instant())
    }
}

@Component
internal class MerchantMenuImageTransaction(
    private val storeAccess: StoreAccessOperations,
    private val images: MenuImageOperations,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
) {
    @Transactional
    fun authorize(
        actorId: UUID,
        storeId: UUID,
    ) {
        storeAccess.requireStoreAccess(actorId, storeId, MENU_IMAGE_ROLES)
    }

    @Transactional
    fun replace(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): MenuImageChange {
        val actor = storeAccess.requireStoreAccess(actorId, storeId, MENU_IMAGE_ROLES)
        val change = images.replace(storeId, menuId, prepared, now)
        if (change.changed) audit(actorId, actor.role, menuId, "MENU_IMAGE_UPDATED", change, now)
        return change
    }

    @Transactional
    fun clear(
        actorId: UUID,
        storeId: UUID,
        menuId: UUID,
        now: Instant,
    ) {
        val actor = storeAccess.requireStoreAccess(actorId, storeId, MENU_IMAGE_ROLES)
        val change = images.clear(storeId, menuId, now)
        if (change.changed) audit(actorId, actor.role, menuId, "MENU_IMAGE_DELETED", change, now)
    }

    private fun audit(
        actorId: UUID,
        actorRole: StoreActorRole,
        menuId: UUID,
        action: String,
        change: MenuImageChange,
        now: Instant,
    ) {
        val correlationId = correlationIds.currentOrCreate()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType =
                        when (actorRole) {
                            StoreActorRole.OWNER -> AuditActorType.STORE_OWNER
                            StoreActorRole.STAFF -> AuditActorType.STORE_STAFF
                        },
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = action,
                    targetType = "Menu",
                    targetId = menuId,
                    occurredAt = now,
                    reason = "MERCHANT_MENU_IMAGE_CHANGE",
                    beforeSummary = imageState(change.previous != null),
                    afterSummary = imageState(change.current != null),
                    correlationId = correlationId,
                    sourceReference = "$action:$correlationId",
                ),
            ),
        )
    }

    private fun imageState(present: Boolean) = mapOf("imageState" to if (present) "PRESENT" else "ABSENT")

    private companion object {
        val MENU_IMAGE_ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
    }
}
