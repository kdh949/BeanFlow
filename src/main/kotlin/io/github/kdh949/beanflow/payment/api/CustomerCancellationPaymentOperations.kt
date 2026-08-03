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

data class ProjectCustomerCancellationPaymentCommand(
    val orderId: UUID,
    val cancellationOrderVersion: Long,
    val paymentExpected: Boolean,
    val correlationId: String,
    val now: Instant,
)

data class CustomerCancellationPaymentProjection(
    val state: String,
    val noticeCode: String? = null,
    val approvedAmountKrw: Long? = null,
    val succeededRefundAmountBeforeCancellationKrw: Long? = null,
    val cancellationRequestedRefundAmountKrw: Long? = null,
    val remainingRefundableAmountKrw: Long? = null,
    val lastUpdatedAt: Instant? = null,
)

interface CustomerCancellationPaymentOperations {
    fun prepare(command: PrepareCustomerCancellationPaymentCommand): CustomerCancellationPaymentSnapshot

    fun findSnapshot(orderId: UUID): CustomerCancellationPaymentSnapshot?

    fun project(command: ProjectCustomerCancellationPaymentCommand): CustomerCancellationPaymentProjection
}
