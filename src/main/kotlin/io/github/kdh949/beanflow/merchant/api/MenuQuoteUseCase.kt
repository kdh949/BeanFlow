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

enum class MenuItemUnavailableReason {
    MENU_REMOVED,
    MENU_NOT_AVAILABLE,
    OPTION_REMOVED,
    OPTION_NOT_AVAILABLE,
    MENU_CONFIGURATION_NOT_AVAILABLE,
}

data class MenuItemUnavailability(
    val reason: MenuItemUnavailableReason,
    val optionId: UUID? = null,
)

sealed interface CurrentMenuLineQuoteResult {
    data class Available(
        val quote: MenuLineQuote,
    ) : CurrentMenuLineQuoteResult

    data class Unavailable(
        val failures: List<MenuItemUnavailability>,
    ) : CurrentMenuLineQuoteResult
}

interface MenuQuoteUseCase {
    fun quote(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): List<MenuLineQuote>

    fun quoteCurrentBatch(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): List<CurrentMenuLineQuoteResult>
}
