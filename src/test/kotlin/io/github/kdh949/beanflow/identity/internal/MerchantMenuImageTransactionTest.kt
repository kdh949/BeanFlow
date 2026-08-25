package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActor
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.merchant.api.MenuImageChange
import io.github.kdh949.beanflow.merchant.api.MenuImageOperations
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImagePointer
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

internal class MerchantMenuImageTransactionTest {
    private val access = mock(StoreAccessOperations::class.java)
    private val images = mock(MenuImageOperations::class.java)
    private val audits = RecordingAuditRecords()
    private val correlations = mock(CorrelationIdSource::class.java)
    private val transaction = MerchantMenuImageTransaction(access, images, audits, correlations)
    private val actorId = UUID.randomUUID()
    private val storeId = UUID.randomUUID()
    private val menuId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-24T00:00:00Z")

    @Test
    fun `STAFF is allowed and recorded as store staff`() {
        `when`(access.requireStoreAccess(actorId, storeId, ROLES))
            .thenReturn(StoreActor(actorId, storeId, StoreActorRole.STAFF))
        val pointer = StorefrontImagePointer(PREPARED.originalKey, PREPARED.thumbnailKey, PREPARED.sha256, now)
        `when`(images.replace(storeId, menuId, PREPARED, now)).thenReturn(MenuImageChange(true, pointer, null))
        `when`(correlations.currentOrCreate()).thenReturn("correlation")
        transaction.replace(actorId, storeId, menuId, PREPARED, now)

        assertThat(audits.appended.single().actorType).isEqualTo(AuditActorType.STORE_STAFF)
        assertThat(audits.appended.single().targetId).isEqualTo(menuId)
    }

    @Test
    fun `menu belonging failure writes no audit`() {
        `when`(access.requireStoreAccess(actorId, storeId, ROLES))
            .thenReturn(StoreActor(actorId, storeId, StoreActorRole.OWNER))
        `when`(images.replace(storeId, menuId, PREPARED, now))
            .thenThrow(DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "menu not found"))

        assertThatThrownBy { transaction.replace(actorId, storeId, menuId, PREPARED, now) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
        assertThat(audits.appended).isEmpty()
    }

    private class RecordingAuditRecords : AuditRecordOperations {
        val appended = mutableListOf<AppendAuditRecordCommand>()

        override fun appendAll(commands: List<AppendAuditRecordCommand>): List<UUID> {
            appended += commands
            return emptyList()
        }
    }

    private companion object {
        val ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
        val PREPARED = PreparedStorefrontImage("original", "thumbnail", "a".repeat(64))
    }
}
