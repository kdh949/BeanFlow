package io.github.kdh949.beanflow.ordering.internal

import java.util.UUID

internal data class ReorderItemFailureDetail(
    val sourceOrderLineId: UUID,
    val lineSequence: Int,
    val menuId: UUID,
    val optionId: UUID?,
    val reason: String,
)

internal class ReorderItemsUnavailableFailure(
    val sourceLineCount: Int,
    val details: List<ReorderItemFailureDetail>,
) : RuntimeException("One or more source items are unavailable") {
    init {
        require(sourceLineCount > 0)
        require(details.isNotEmpty())
    }
}

internal data class ReorderItemsUnavailableErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String,
    val details: List<ReorderItemFailureDetail>,
)

internal data class ReorderPriceComparison(
    val hasPriceChanges: Boolean,
    val sourceSubtotalKrw: Long,
    val currentSubtotalKrw: Long,
    val subtotalDifferenceKrw: Long,
    val items: List<ReorderLinePriceChange>,
)

internal data class ReorderLinePriceChange(
    val sourceOrderLineId: UUID,
    val lineSequence: Int,
    val menuId: UUID,
    val quantity: Long,
    val sourceUnitPriceKrw: Long,
    val currentUnitPriceKrw: Long,
    val sourceLineGrossKrw: Long,
    val currentLineGrossKrw: Long,
    val lineDifferenceKrw: Long,
)

internal data class PendingPaymentReorderOrderCreationResponse(
    val order: OrderResponse,
    val priceComparison: ReorderPriceComparison,
)

internal data class BenefitOnlyReorderOrderCreationResponse(
    val order: BenefitOnlyOrderResponse,
    val payment: BenefitOnlyPaymentResponse,
    val priceComparison: ReorderPriceComparison,
)
