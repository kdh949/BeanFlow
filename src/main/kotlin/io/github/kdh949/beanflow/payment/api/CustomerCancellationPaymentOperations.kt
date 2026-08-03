package io.github.kdh949.beanflow.payment.api

import java.time.Instant
import java.util.UUID

data class PrepareCustomerCancellationPaymentCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val customerReasonCode: String,
    val correlationId: String,
    val now: Instant,
)

data class CustomerCancellationPaymentSnapshot(
    val snapshotId: UUID,
    val paymentId: UUID,
    val paymentType: String,
    val approvedAmountKrw: Long,
    val succeededRefundAmountBeforeCancellationKrw: Long,
    val requestedRefundAmountKrw: Long,
    val refundId: UUID?,
    val refundSourceReference: String?,
    val providerIdempotencyKey: String?,
    val updatedAt: Instant,
) {
    val paymentRecoveryRequired: Boolean
        get() = requestedRefundAmountKrw > 0
}

interface CustomerCancellationPaymentOperations {
    fun prepare(command: PrepareCustomerCancellationPaymentCommand): CustomerCancellationPaymentSnapshot

    fun findSnapshot(orderId: UUID): CustomerCancellationPaymentSnapshot?
}
