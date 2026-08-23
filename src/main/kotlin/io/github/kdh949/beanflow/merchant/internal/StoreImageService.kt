package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreImageChange
import io.github.kdh949.beanflow.merchant.api.StoreImageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageCleanupRequestedV1
import io.github.kdh949.beanflow.merchant.api.StorefrontImagePointer
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class StoreImageService(
    private val stores: StoreJpaRepository,
    private val events: ApplicationEventPublisher,
) : StoreImageOperations {
    @Transactional(readOnly = true)
    override fun find(storeId: UUID): StorefrontImagePointer? = requiredStore(storeId).imagePointer()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(
        storeId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): StoreImageChange {
        val store = requiredStoreLocked(storeId)
        val previous = store.imagePointer()
        if (previous?.sha256 == prepared.sha256) return StoreImageChange(false, previous, previous)
        store.replaceImage(prepared.originalKey, prepared.thumbnailKey, prepared.sha256, now)
        val current = requireNotNull(store.imagePointer())
        previous?.publishCleanup()
        return StoreImageChange(true, current, previous)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun clear(
        storeId: UUID,
        now: Instant,
    ): StoreImageChange {
        val store = requiredStoreLocked(storeId)
        val previous = store.imagePointer() ?: return StoreImageChange(false, null, null)
        store.clearImage()
        previous.publishCleanup()
        return StoreImageChange(true, null, previous)
    }

    private fun requiredStore(storeId: UUID): StoreEntity =
        stores.findById(storeId).orElseThrow {
            DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store not found", targetReference = storeId.toString())
        }

    private fun requiredStoreLocked(storeId: UUID): StoreEntity =
        stores.findByIdForUpdate(storeId)
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store not found", targetReference = storeId.toString())

    private fun StoreEntity.imagePointer(): StorefrontImagePointer? {
        val originalKey = imageOriginalKey ?: return null
        return StorefrontImagePointer(
            originalKey = originalKey,
            thumbnailKey = requireNotNull(imageThumbnailKey),
            sha256 = requireNotNull(imageSha256),
            updatedAt = requireNotNull(imageUpdatedAt),
        )
    }

    private fun StorefrontImagePointer.publishCleanup() {
        events.publishEvent(StorefrontImageCleanupRequestedV1(originalKey, thumbnailKey))
    }
}
