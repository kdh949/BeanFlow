package io.github.kdh949.beanflow.merchant.api

import java.util.UUID

data class QuoteOrderLine(
	val menuId: UUID,
	val optionIds: List<UUID>,
	val quantity: Long,
)

data class SellableUnitRequirement(
	val sellableUnitId: UUID,
	val quantityPerLineUnit: Long,
)

data class OptionSnapshot(
	val optionId: UUID,
	val name: String,
	val additionalPriceKrw: Long,
)

data class MenuLineQuote(
	val menuId: UUID,
	val menuName: String,
	val optionSnapshots: List<OptionSnapshot>,
	val unitPriceKrw: Long,
	val quantity: Long,
	val sellableUnitRequirements: List<SellableUnitRequirement>,
)

interface MenuQuoteUseCase {
	fun quote(storeId: UUID, lines: List<QuoteOrderLine>): List<MenuLineQuote>
}
