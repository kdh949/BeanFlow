package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

enum class StorefrontImageTarget(
    val objectPrefix: String,
) {
    STORE("stores"),
    MENU("menus"),
}

data class StorefrontImageUpload(
    val bytes: ByteArray,
    val contentType: String?,
)

data class PreparedStorefrontImage(
    val originalKey: String,
    val thumbnailKey: String,
    val sha256: String,
)

data class NormalizedStorefrontImageUpload(
    val original: ByteArray,
    val thumbnail: ByteArray,
    val contentType: String,
    val extension: String,
    val sha256: String,
)

data class StorefrontImagePointer(
    val originalKey: String,
    val thumbnailKey: String,
    val sha256: String,
    val updatedAt: Instant,
)

data class StorefrontImageAccess(
    val url: String,
    val expiresAt: Instant,
)

data class StoreImageChange(
    val changed: Boolean,
    val current: StorefrontImagePointer?,
    val previous: StorefrontImagePointer?,
)

/** External AIStor work. Callers must invoke it outside their database transaction. */
interface StorefrontImageStorageOperations {
    fun normalize(upload: StorefrontImageUpload): NormalizedStorefrontImageUpload

    fun store(
        target: StorefrontImageTarget,
        targetId: UUID,
        normalized: NormalizedStorefrontImageUpload,
    ): PreparedStorefrontImage

    /** Generates a signed URL locally; it performs no object-store network request. */
    fun access(thumbnailKey: String): StorefrontImageAccess

    fun delete(
        originalKey: String,
        thumbnailKey: String,
    )
}

/** Store pointer operations. Mutations require a caller-owned local transaction. */
interface StoreImageOperations {
    fun find(storeId: UUID): StorefrontImagePointer?

    fun replace(
        storeId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): StoreImageChange

    fun clear(
        storeId: UUID,
        now: Instant,
    ): StoreImageChange
}

/** Durable cleanup request handled after the pointer transaction commits. */
data class StorefrontImageCleanupRequestedV1(
    val originalKey: String,
    val thumbnailKey: String,
)
