package io.github.kdh949.beanflow.ordering.internal

import java.time.Instant
import java.util.UUID

internal data class PendingPaymentOrderCreationResponse(
	val order: OrderResponse,
)

internal data class OrderResponse(
	val orderId: UUID,
	val storeId: UUID,
	val state: String,
	val reservationExpiresAt: Instant?,
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
