package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import org.springframework.stereotype.Component

/** Converts a stored thumbnail key to a public response without an AIStor network call. */
@Component
internal class StorefrontImageViewResolver(
    private val storage: StorefrontImageStorageOperations,
) {
    fun resolve(thumbnailKey: String?): StorefrontImageView? =
        thumbnailKey?.let { storage.access(it) }?.let { StorefrontImageView(it.url, it.expiresAt) }
}
