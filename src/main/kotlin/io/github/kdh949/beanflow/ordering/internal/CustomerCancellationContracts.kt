package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import java.time.Instant
import java.util.UUID

internal data class CustomerCancellationRequest(
    val reasonCode: CustomerCancellationReasonCode,
    val detail: String?,
)

internal data class CancellationRefundRecoverySummary(
    val state: String,
    val noticeCode: String? = null,
    val approvedAmountKrw: Long? = null,
    val succeededRefundAmountBeforeCancellationKrw: Long? = null,
    val cancellationRequestedRefundAmountKrw: Long? = null,
    val remainingRefundableAmountKrw: Long? = null,
    val lastUpdatedAt: Instant? = null,
)

internal data class CustomerCancellationResponse(
    val orderId: UUID,
    val orderState: String,
    val reasonCode: CustomerCancellationReasonCode,
    val paymentRecovery: CancellationRefundRecoverySummary,
    val cancelledAt: Instant,
    val correlationId: String,
)

internal data class CustomerCancellationHttpResult(
    val status: Int,
    val body: String,
)
