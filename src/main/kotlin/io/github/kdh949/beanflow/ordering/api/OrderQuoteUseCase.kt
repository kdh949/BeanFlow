package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

data class OrderQuoteCommand(
    val customerId: UUID,
    val storeId: UUID,
    val pickupSlotId: UUID,
    val lines: List<CreateOrderLineCommand>,
    val couponIssuanceId: UUID?,
    val pointsToUseKrw: Long,
)

data class OrderQuoteStore(
    val storeId: UUID,
    val name: String,
)

data class OrderQuotePickupWindow(
    val startsAt: Instant,
    val endsAt: Instant,
)

data class OrderQuoteLine(
    val menuId: UUID,
    val menuName: String,
    val quantity: Long,
    val optionNames: List<String>,
    val lineTotalKrw: Long,
)

data class OrderQuotePricing(
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String = "KRW",
)

data class OrderQuoteResponse(
    val quotedAt: Instant,
    val quoteFingerprint: String,
    val store: OrderQuoteStore,
    val pickupWindow: OrderQuotePickupWindow,
    val lines: List<OrderQuoteLine>,
    val pricing: OrderQuotePricing,
    val guarantee: String = "NONE",
)

interface OrderQuoteUseCase {
    fun quote(command: OrderQuoteCommand): OrderQuoteResponse
}
