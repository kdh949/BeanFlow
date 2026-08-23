package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreImageChange
import io.github.kdh949.beanflow.merchant.api.StoreImageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImagePointer
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

internal class MerchantStoreImageTransactionTest {
    private val access = mock(StoreAccessOperations::class.java)
    private val images = mock(StoreImageOperations::class.java)
    private val audits = mock(AuditRecordOperations::class.java)
    private val correlationIds = mock(CorrelationIdSource::class.java)
    private val transaction = MerchantStoreImageTransaction(access, images, audits, correlationIds)
    private val actorId = UUID.randomUUID()
    private val storeId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-24T00:00:00Z")

    @Test
    fun `store image authorization requires OWNER and rejects STAFF before a pointer write`() {
        doThrow(DomainFailure(FailureCode.ACCESS_DENIED, "owner required"))
            .`when`(access)
            .requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))

        assertThatThrownBy { transaction.replace(actorId, storeId, PREPARED, now) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.ACCESS_DENIED)

        verify(access).requireStoreAccess(actorId, storeId, setOf(StoreActorRole.OWNER))
        verifyNoInteractions(images, audits)
    }

    @Test
    fun `same hash race writes no duplicate audit`() {
        val pointer = StorefrontImagePointer(PREPARED.originalKey, PREPARED.thumbnailKey, PREPARED.sha256, now.minusSeconds(1))
        `when`(images.replace(storeId, PREPARED, now)).thenReturn(StoreImageChange(false, pointer, pointer))

        transaction.replace(actorId, storeId, PREPARED, now)

        verify(audits, never()).appendAll(anyList())
    }

    @Test
    fun `changed pointer appends audit in the same transaction boundary`() {
        val pointer = StorefrontImagePointer(PREPARED.originalKey, PREPARED.thumbnailKey, PREPARED.sha256, now)
        `when`(images.replace(storeId, PREPARED, now)).thenReturn(StoreImageChange(true, pointer, null))
        `when`(correlationIds.currentOrCreate()).thenReturn("correlation")

        transaction.replace(actorId, storeId, PREPARED, now)

        verify(audits).appendAll(anyList())
    }

    private companion object {
        val PREPARED = PreparedStorefrontImage("original", "thumbnail", "a".repeat(64))
    }
}
