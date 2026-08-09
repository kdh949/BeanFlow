package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode

internal object FastReorderPriceComparison {
    fun calculate(
        sourceLines: List<OrderLineEntity>,
        currentOrder: Order,
    ): ReorderPriceComparison {
        if (sourceLines.size != currentOrder.lines.size) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Source and current order lines do not match")
        }
        val changes =
            sourceLines.zip(currentOrder.lines).mapNotNull { (source, current) ->
                if (
                    source.lineSequence != current.lineSequence ||
                    source.menuId != current.menuId ||
                    source.quantity != current.quantity
                ) {
                    throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Source and current line identity changed")
                }
                if (source.unitPriceKrw == current.unitPriceKrw && source.grossKrw == current.grossKrw) {
                    null
                } else {
                    ReorderLinePriceChange(
                        sourceOrderLineId = source.id,
                        lineSequence = source.lineSequence,
                        menuId = source.menuId,
                        quantity = source.quantity,
                        sourceUnitPriceKrw = source.unitPriceKrw,
                        currentUnitPriceKrw = current.unitPriceKrw,
                        sourceLineGrossKrw = source.grossKrw,
                        currentLineGrossKrw = current.grossKrw,
                        lineDifferenceKrw = subtract(current.grossKrw, source.grossKrw),
                    )
                }
            }
        val sourceSubtotal = sum(sourceLines.map(OrderLineEntity::grossKrw))
        val currentSubtotal = sum(currentOrder.lines.map { it.grossKrw })
        return ReorderPriceComparison(
            hasPriceChanges = changes.isNotEmpty(),
            sourceSubtotalKrw = sourceSubtotal,
            currentSubtotalKrw = currentSubtotal,
            subtotalDifferenceKrw = subtract(currentSubtotal, sourceSubtotal),
            items = changes,
        )
    }

    private fun sum(values: List<Long>): Long =
        try {
            values.fold(0L, Math::addExact)
        } catch (_: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Order subtotal exceeds supported KRW range")
        }

    private fun subtract(
        current: Long,
        source: Long,
    ): Long =
        try {
            Math.subtractExact(current, source)
        } catch (_: ArithmeticException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Price difference exceeds supported KRW range")
        }
}
