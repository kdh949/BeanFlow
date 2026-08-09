package io.github.kdh949.beanflow.merchant.internal.domain

import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuItemUnavailability
import io.github.kdh949.beanflow.merchant.api.MenuItemUnavailableReason
import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.OptionSnapshot
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.util.UUID

data class StoreDefinition(
    val id: UUID,
    val acceptingOrders: Boolean,
    val pickupEnabled: Boolean,
)

data class MenuOptionDefinition(
    val id: UUID,
    val name: String,
    val additionalPriceKrw: Long,
    val available: Boolean,
)

data class MenuConfigurationDefinition(
    val optionIds: Set<UUID>,
    val available: Boolean,
    val requirements: List<SellableUnitRequirement>,
)

data class MenuDefinition(
    val id: UUID,
    val storeId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
    val options: List<MenuOptionDefinition>,
    val configurations: List<MenuConfigurationDefinition>,
)

class MenuQuoteCalculator {
    fun quote(
        store: StoreDefinition,
        menus: Map<UUID, MenuDefinition>,
        lines: List<QuoteOrderLine>,
    ): List<MenuLineQuote> {
        if (!store.acceptingOrders || !store.pickupEnabled) {
            fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Store is not accepting pickup orders")
        }
        if (lines.isEmpty()) {
            fail(FailureCode.INVALID_REQUEST, "At least one order line is required")
        }

        return lines.map { request ->
            quoteLine(store, menus[request.menuId], request)
        }
    }

    fun quoteCurrentBatch(
        store: StoreDefinition,
        menus: Map<UUID, MenuDefinition>,
        lines: List<QuoteOrderLine>,
    ): List<CurrentMenuLineQuoteResult> {
        if (!store.acceptingOrders || !store.pickupEnabled) {
            fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Store is not accepting pickup orders")
        }
        if (lines.isEmpty()) {
            fail(FailureCode.INVALID_REQUEST, "At least one order line is required")
        }
        lines.forEach(::validateRequest)
        return lines.map { request -> quoteCurrentLine(store, menus[request.menuId], request) }
    }

    private fun quoteLine(
        store: StoreDefinition,
        menu: MenuDefinition?,
        request: QuoteOrderLine,
    ): MenuLineQuote {
        validateRequest(request)
        if (menu == null || menu.storeId != store.id) {
            fail(FailureCode.INVALID_REQUEST, "Menu does not belong to the requested store")
        }
        if (!menu.available) {
            fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu is not available")
        }
        requireNonNegative(menu.basePriceKrw, "Menu base price")

        val optionsById = menu.options.associateBy(MenuOptionDefinition::id)
        val normalizedOptionIds = request.optionIds.sorted()
        val selectedOptions =
            normalizedOptionIds.map { optionId ->
                val option =
                    optionsById[optionId]
                        ?: fail(FailureCode.INVALID_REQUEST, "Option does not belong to the menu")
                if (!option.available) {
                    fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu option is not available")
                }
                requireNonNegative(option.additionalPriceKrw, "Option additional price")
                option
            }

        val configuration =
            menu.configurations.singleOrNull {
                it.optionIds == normalizedOptionIds.toSet()
            } ?: fail(FailureCode.INVALID_REQUEST, "No menu configuration matches the selected options")
        if (!configuration.available) {
            fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu configuration is not available")
        }
        return buildQuote(menu, selectedOptions, configuration, request, FailureCode.INVALID_REQUEST)
    }

    private fun quoteCurrentLine(
        store: StoreDefinition,
        menu: MenuDefinition?,
        request: QuoteOrderLine,
    ): CurrentMenuLineQuoteResult {
        if (menu == null || menu.storeId != store.id) {
            return unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_REMOVED))
        }
        if (!menu.available) {
            return unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_NOT_AVAILABLE))
        }
        requireNonNegative(menu.basePriceKrw, "Menu base price", FailureCode.DEPENDENCY_UNAVAILABLE)

        val optionsById = menu.options.associateBy(MenuOptionDefinition::id)
        val normalizedOptionIds = request.optionIds.sorted()
        val optionFailures =
            normalizedOptionIds
                .mapNotNull { optionId ->
                    val option = optionsById[optionId]
                    when {
                        option == null -> {
                            MenuItemUnavailability(MenuItemUnavailableReason.OPTION_REMOVED, optionId)
                        }

                        !option.available -> {
                            MenuItemUnavailability(MenuItemUnavailableReason.OPTION_NOT_AVAILABLE, optionId)
                        }

                        else -> {
                            null
                        }
                    }
                }.sortedWith(compareBy<MenuItemUnavailability>({ it.reason.ordinal }, { it.optionId }))
        if (optionFailures.isNotEmpty()) return CurrentMenuLineQuoteResult.Unavailable(optionFailures)

        val selectedOptions = normalizedOptionIds.map { optionsById.getValue(it) }
        selectedOptions.forEach {
            requireNonNegative(it.additionalPriceKrw, "Option additional price", FailureCode.DEPENDENCY_UNAVAILABLE)
        }
        val configuration =
            menu.configurations.singleOrNull { it.optionIds == normalizedOptionIds.toSet() }
                ?: return unavailable(
                    MenuItemUnavailability(MenuItemUnavailableReason.MENU_CONFIGURATION_NOT_AVAILABLE),
                )
        if (!configuration.available) {
            return unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_CONFIGURATION_NOT_AVAILABLE))
        }
        return CurrentMenuLineQuoteResult.Available(
            buildQuote(menu, selectedOptions, configuration, request, FailureCode.DEPENDENCY_UNAVAILABLE),
        )
    }

    private fun buildQuote(
        menu: MenuDefinition,
        selectedOptions: List<MenuOptionDefinition>,
        configuration: MenuConfigurationDefinition,
        request: QuoteOrderLine,
        invalidOwnerCode: FailureCode,
    ): MenuLineQuote {
        if (configuration.requirements.isEmpty() || configuration.requirements.any { it.quantityPerLineUnit < 1 }) {
            fail(invalidOwnerCode, "Menu configuration requirements are invalid")
        }
        val unitPrice =
            selectedOptions.fold(menu.basePriceKrw) { price, option ->
                try {
                    Math.addExact(price, option.additionalPriceKrw)
                } catch (_: ArithmeticException) {
                    fail(invalidOwnerCode, "Unit price exceeds supported KRW range")
                }
            }
        return MenuLineQuote(
            menuId = menu.id,
            menuName = menu.name,
            optionSnapshots = selectedOptions.map { OptionSnapshot(it.id, it.name, it.additionalPriceKrw) },
            unitPriceKrw = unitPrice,
            quantity = request.quantity,
            sellableUnitRequirements = configuration.requirements.sortedBy(SellableUnitRequirement::sellableUnitId),
        )
    }

    private fun validateRequest(request: QuoteOrderLine) {
        if (request.quantity < 1) fail(FailureCode.INVALID_REQUEST, "Order line quantity must be positive")
        if (request.optionIds.size != request.optionIds.toSet().size) {
            fail(FailureCode.INVALID_REQUEST, "Option IDs must not contain duplicates")
        }
    }

    private fun unavailable(vararg failures: MenuItemUnavailability): CurrentMenuLineQuoteResult.Unavailable =
        CurrentMenuLineQuoteResult.Unavailable(failures.toList())

    private fun requireNonNegative(
        value: Long,
        name: String,
    ) {
        requireNonNegative(value, name, FailureCode.INVALID_REQUEST)
    }

    private fun requireNonNegative(
        value: Long,
        name: String,
        failureCode: FailureCode,
    ) {
        if (value < 0) {
            fail(failureCode, "$name must not be negative")
        }
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}
