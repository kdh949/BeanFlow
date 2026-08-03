package io.github.kdh949.beanflow.operations.api

import java.time.Instant
import java.util.UUID

data class InspectCustomerCancellationMissingRefundCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
)

data class CustomerCancellationMissingRefundRepairSnapshot(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val paymentId: UUID,
    val snapshotId: UUID,
    val snapshotVersion: Long,
    val refundId: UUID,
    val requestedAmountKrw: Long,
    val refundSourceFingerprint: String,
    val providerKeyFingerprint: String,
)

data class RecreateCustomerCancellationMissingRefundCommand(
    val proposalId: UUID,
    val expected: CustomerCancellationMissingRefundRepairSnapshot,
    val now: Instant,
)

sealed interface RecreateCustomerCancellationMissingRefundResult {
    data class Succeeded(
        val refundId: UUID,
    ) : RecreateCustomerCancellationMissingRefundResult

    data class Stale(
        val errorCode: String,
    ) : RecreateCustomerCancellationMissingRefundResult
}

interface CustomerCancellationMissingRefundRepairOperations {
    fun inspect(command: InspectCustomerCancellationMissingRefundCommand): CustomerCancellationMissingRefundRepairSnapshot

    fun recreateForLookup(command: RecreateCustomerCancellationMissingRefundCommand): RecreateCustomerCancellationMissingRefundResult
}
