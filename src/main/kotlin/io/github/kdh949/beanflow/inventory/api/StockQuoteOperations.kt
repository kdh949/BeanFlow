package io.github.kdh949.beanflow.inventory.api

import java.util.UUID

data class StockQuoteItem(
    val sellableUnitId: UUID,
    val storeId: UUID,
    val requiredQuantity: Long,
    val availableQuantity: Long,
    val reservedQuantity: Long,
    val confirmedQuantity: Long,
    val version: Long,
)

interface StockQuoteOperations {
    fun inspect(
        storeId: UUID,
        requirements: List<StockRequirement>,
    ): List<StockQuoteItem>

    fun lockForOrderCreation(
        storeId: UUID,
        requirements: List<StockRequirement>,
    ): List<StockQuoteItem>
}
