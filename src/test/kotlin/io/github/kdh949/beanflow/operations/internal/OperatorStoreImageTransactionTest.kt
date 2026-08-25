package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StoreImageOperations
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant
import java.util.UUID

internal class OperatorStoreImageTransactionTest {
    private val authorization = mock(OperatorPermissionAuthorization::class.java)
    private val images = mock(StoreImageOperations::class.java)
    private val audits = mock(AuditRecordOperations::class.java)
    private val transaction = OperatorStoreImageTransaction(authorization, images, audits, mock(CorrelationIdSource::class.java))
    private val actorId = UUID.randomUUID()
    private val storeId = UUID.randomUUID()

    @Test
    fun `operator requires a nonblank access reason before checking the grant`() {
        assertThatThrownBy { transaction.replace(actorId, storeId, "  ", PREPARED, NOW) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.INVALID_REQUEST)
        verifyNoInteractions(authorization, images, audits)
    }

    @Test
    fun `missing STORE_MEDIA_MANAGE grant prevents pointer and audit writes`() {
        doThrow(DomainFailure(FailureCode.ACCESS_DENIED, "grant required"))
            .`when`(authorization)
            .requireActive(actorId, OperatorPermission.STORE_MEDIA_MANAGE)

        assertThatThrownBy { transaction.replace(actorId, storeId, "incident correction", PREPARED, NOW) }
            .isInstanceOf(DomainFailure::class.java)
            .extracting("code")
            .isEqualTo(FailureCode.ACCESS_DENIED)

        verify(authorization).requireActive(actorId, OperatorPermission.STORE_MEDIA_MANAGE)
        verifyNoInteractions(images, audits)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
        val PREPARED = PreparedStorefrontImage("original", "thumbnail", "a".repeat(64))
    }
}
