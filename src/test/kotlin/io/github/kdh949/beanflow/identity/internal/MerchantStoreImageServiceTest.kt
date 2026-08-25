package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreImageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImagePointer
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class MerchantStoreImageServiceTest {
    private val transactions = mock(MerchantStoreImageTransaction::class.java)
    private val images = mock(StoreImageOperations::class.java)
    private val storage = mock(StorefrontImageStorageOperations::class.java)
    private val now = Instant.parse("2026-08-24T00:00:00Z")
    private val service = MerchantStoreImageService(transactions, images, storage, Clock.fixed(now, ZoneOffset.UTC))
    private val actorId = UUID.randomUUID()
    private val storeId = UUID.randomUUID()
    private val upload = StorefrontImageUpload(byteArrayOf(1), "image/jpeg")
    private val normalized = NormalizedStorefrontImageUpload(byteArrayOf(2), byteArrayOf(3), "image/jpeg", "jpg", HASH)

    @Test
    fun `same normalized hash returns a new signed URL without object PUT pointer write or audit transaction`() {
        val pointer = StorefrontImagePointer("original", "thumbnail", HASH, now.minusSeconds(10))
        val access = StorefrontImageAccess("https://media.test/signed", now.plusSeconds(900))
        `when`(images.find(storeId)).thenReturn(pointer)
        `when`(storage.normalize(upload)).thenReturn(normalized)
        `when`(storage.access(pointer.thumbnailKey)).thenReturn(access)

        assertThat(service.replace(actorId, storeId, upload)).isEqualTo(access)

        verify(transactions).authorize(actorId, storeId)
        verify(storage, never()).store(StorefrontImageTarget.STORE, storeId, normalized)
        verify(transactions, never()).replace(actorId, storeId, prepared(), now)
    }

    @Test
    fun `unresolved AIStor PUT returns 503 before the database pointer transaction`() {
        `when`(images.find(storeId)).thenReturn(null)
        `when`(storage.normalize(upload)).thenReturn(normalized)
        `when`(storage.store(StorefrontImageTarget.STORE, storeId, normalized))
            .thenThrow(DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "unresolved"))

        assertThatThrownBy { service.replace(actorId, storeId, upload) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        verify(transactions, never()).replace(actorId, storeId, prepared(), now)
    }

    @Test
    fun `successful upload is signed before the final pointer transaction`() {
        val prepared = prepared()
        val access = StorefrontImageAccess("https://media.test/signed", now.plusSeconds(900))
        `when`(images.find(storeId)).thenReturn(null)
        `when`(storage.normalize(upload)).thenReturn(normalized)
        `when`(storage.store(StorefrontImageTarget.STORE, storeId, normalized)).thenReturn(prepared)
        `when`(storage.access(prepared.thumbnailKey)).thenReturn(access)

        assertThat(service.replace(actorId, storeId, upload)).isEqualTo(access)

        val order = org.mockito.Mockito.inOrder(transactions, images, storage)
        order.verify(transactions).authorize(actorId, storeId)
        order.verify(images).find(storeId)
        order.verify(storage).normalize(upload)
        order.verify(storage).store(StorefrontImageTarget.STORE, storeId, normalized)
        order.verify(storage).access(prepared.thumbnailKey)
        order.verify(transactions).replace(actorId, storeId, prepared, now)
    }

    private fun prepared() = PreparedStorefrontImage("original", "thumbnail", HASH)

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
