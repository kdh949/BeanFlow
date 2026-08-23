package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageCleanupRequestedV1
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class StorefrontImageCleanupListener(
    private val storage: StorefrontImageStorageOperations,
    private val meterRegistry: MeterRegistry,
) {
    @ApplicationModuleListener(id = "beanflow.storefront-image.cleanup-v1")
    fun cleanup(event: StorefrontImageCleanupRequestedV1) {
        try {
            storage.delete(event.originalKey, event.thumbnailKey)
            record("succeeded")
        } catch (failure: RuntimeException) {
            record("failed")
            throw failure
        }
    }

    private fun record(outcome: String) {
        meterRegistry.counter("beanflow.media.cleanup.publication", "outcome", outcome).increment()
    }
}
