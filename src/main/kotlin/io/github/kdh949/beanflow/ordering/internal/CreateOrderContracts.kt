package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class PendingPaymentOrderCreationResponse(
    val order: OrderResponse,
)

internal data class BenefitOnlyOrderCreationResponse(
    val order: BenefitOnlyOrderResponse,
    val payment: BenefitOnlyPaymentResponse,
)

internal data class BenefitOnlyOrderResponse(
    val orderId: UUID,
    val storeId: UUID,
    val publicReference: String,
    val pickupNumber: String,
    val pickupBusinessDate: LocalDate,
    val storeName: String,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val state: String,
    val lines: List<OrderLineResponse>,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class BenefitOnlyPaymentResponse(
    val paymentId: UUID,
    val orderId: UUID,
    val type: String,
    val approvalState: String,
    val approvedAmountKrw: Long,
    val currency: String,
    val updatedAt: Instant,
    val correlationId: String,
)

internal data class OrderResponse(
    val orderId: UUID,
    val storeId: UUID,
    val publicReference: String,
    val pickupNumber: String,
    val pickupBusinessDate: LocalDate,
    val storeName: String,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val state: String,
    val reservationExpiresAt: Instant?,
    val paymentRecovery: CancellationRefundRecoverySummary?,
    val paidAt: Instant?,
    val acceptanceWarningAt: Instant?,
    val acceptanceWarningRequestedAt: Instant?,
    val acceptanceDeadlineAt: Instant?,
    val acceptedAt: Instant?,
    val rejectedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationCause: OrderCancellationCause?,
    val cancellationReasonCode: CustomerCancellationReasonCode?,
    val rejectionReason: String?,
    val lines: List<OrderLineResponse>,
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class OrderLineResponse(
    val orderLineId: UUID,
    val menuId: UUID,
    val menuName: String,
    val optionNames: List<String>,
    val unitPriceKrw: Long,
    val quantity: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val cashPaidKrw: Long,
)

internal data class ErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String,
    val details: List<ErrorDetail> = emptyList(),
)

internal data class ErrorDetail(
    val field: String? = null,
    val reason: String,
)
