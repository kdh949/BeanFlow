package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageCleanupRequestedV1
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class StorefrontImageCleanupListener(
    private val storage: StorefrontImageStorageOperations,
) {
    @ApplicationModuleListener(id = "beanflow.storefront-image.cleanup-v1")
    fun cleanup(event: StorefrontImageCleanupRequestedV1) {
        storage.delete(event.originalKey, event.thumbnailKey)
    }
}
