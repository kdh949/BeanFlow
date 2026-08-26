package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

internal data class CustomerOrderSummaryResponse(
    val orderReference: String,
    val pickupNumber: String,
    val storeName: String,
    val status: String,
    val orderedAt: Instant,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val totalAmountKrw: Long,
    val currency: String,
    val itemSummary: String,
    val allowedActions: List<CustomerOrderAllowedAction>,
)

internal data class CustomerOrderPageResponse(
    val items: List<CustomerOrderSummaryResponse>,
    val page: CustomerOrderPageInfoResponse,
)

internal data class CustomerOrderPageInfoResponse(
    val nextCursor: String?,
)

internal data class CustomerOrderLineResponse(
    val lineSequence: Int,
    val menuName: String,
    val optionNames: List<String>,
    val quantity: Long,
    val lineTotalKrw: Long,
)

internal data class CustomerOrderPricingResponse(
    val subtotalKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val payableKrw: Long,
    val currency: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class OrderLifecycleResponse(
    val paidAt: Instant?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val completedAt: Instant?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerOrderDetailResponse(
    val orderReference: String,
    /**
     * Opaque store identifier supplied by the server so the reorder screen can
     * read that store's current pickup slots. No screen accepts it as input.
     */
    val storeId: UUID,
    val pickupNumber: String,
    val storeName: String,
    val status: String,
    val orderedAt: Instant,
    val pickupWindowStart: Instant,
    val pickupWindowEnd: Instant,
    val pricing: CustomerOrderPricingResponse,
    val lifecycle: OrderLifecycleResponse?,
    val lines: List<CustomerOrderLineResponse>,
    val allowedActions: List<CustomerOrderAllowedAction>,
    val paymentRecovery: CancellationRefundRecoverySummary? = null,
)
