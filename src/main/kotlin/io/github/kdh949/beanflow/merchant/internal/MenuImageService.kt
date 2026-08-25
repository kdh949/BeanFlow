package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.MenuImageChange
import io.github.kdh949.beanflow.merchant.api.MenuImageOperations
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
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
internal class MenuImageService(
    private val menus: MenuJpaRepository,
    private val events: ApplicationEventPublisher,
) : MenuImageOperations {
    @Transactional(readOnly = true)
    override fun find(
        storeId: UUID,
        menuId: UUID,
    ): StorefrontImagePointer? = requiredMenu(storeId, menuId).imagePointer()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(
        storeId: UUID,
        menuId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): MenuImageChange {
        val menu = requiredMenuLocked(storeId, menuId)
        val previous = menu.imagePointer()
        if (previous?.sha256 == prepared.sha256) return MenuImageChange(false, previous, previous)
        menu.replaceImage(prepared.originalKey, prepared.thumbnailKey, prepared.sha256, now)
        val current = requireNotNull(menu.imagePointer())
        previous?.publishCleanup()
        return MenuImageChange(true, current, previous)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun clear(
        storeId: UUID,
        menuId: UUID,
        now: Instant,
    ): MenuImageChange {
        val menu = requiredMenuLocked(storeId, menuId)
        val previous = menu.imagePointer() ?: return MenuImageChange(false, null, null)
        menu.clearImage()
        previous.publishCleanup()
        return MenuImageChange(true, null, previous)
    }

    private fun requiredMenu(
        storeId: UUID,
        menuId: UUID,
    ): MenuEntity = menus.findByIdAndStoreId(menuId, storeId) ?: throw notFound(menuId)

    private fun requiredMenuLocked(
        storeId: UUID,
        menuId: UUID,
    ): MenuEntity = menus.findByIdAndStoreIdForUpdate(menuId, storeId) ?: throw notFound(menuId)

    private fun notFound(menuId: UUID) =
        DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Menu not found", targetReference = menuId.toString())

    private fun MenuEntity.imagePointer(): StorefrontImagePointer? {
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
