package io.github.kdh949.beanflow.merchant.internal.domain

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

	private fun quoteLine(
		store: StoreDefinition,
		menu: MenuDefinition?,
		request: QuoteOrderLine,
	): MenuLineQuote {
		if (request.quantity < 1) {
			fail(FailureCode.INVALID_REQUEST, "Order line quantity must be positive")
		}
		if (request.optionIds.size != request.optionIds.toSet().size) {
			fail(FailureCode.INVALID_REQUEST, "Option IDs must not contain duplicates")
		}
		if (menu == null || menu.storeId != store.id) {
			fail(FailureCode.INVALID_REQUEST, "Menu does not belong to the requested store")
		}
		if (!menu.available) {
			fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu is not available")
		}
		requireNonNegative(menu.basePriceKrw, "Menu base price")

		val optionsById = menu.options.associateBy(MenuOptionDefinition::id)
		val normalizedOptionIds = request.optionIds.sorted()
		val selectedOptions = normalizedOptionIds.map { optionId ->
			val option = optionsById[optionId]
				?: fail(FailureCode.INVALID_REQUEST, "Option does not belong to the menu")
			if (!option.available) {
				fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu option is not available")
			}
			requireNonNegative(option.additionalPriceKrw, "Option additional price")
			option
		}

		val configuration = menu.configurations.singleOrNull {
			it.optionIds == normalizedOptionIds.toSet()
		} ?: fail(FailureCode.INVALID_REQUEST, "No menu configuration matches the selected options")
		if (!configuration.available) {
			fail(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE, "Menu configuration is not available")
		}
		if (configuration.requirements.isEmpty() ||
			configuration.requirements.any { it.quantityPerLineUnit < 1 }
		) {
			fail(FailureCode.INVALID_REQUEST, "Menu configuration requirements are invalid")
		}

		val unitPrice = selectedOptions.fold(menu.basePriceKrw) { price, option ->
			try {
				Math.addExact(price, option.additionalPriceKrw)
			} catch (_: ArithmeticException) {
				fail(FailureCode.INVALID_REQUEST, "Unit price exceeds supported KRW range")
			}
		}

		return MenuLineQuote(
			menuId = menu.id,
			menuName = menu.name,
			optionSnapshots = selectedOptions.map {
				OptionSnapshot(it.id, it.name, it.additionalPriceKrw)
			}.toList(),
			unitPriceKrw = unitPrice,
			quantity = request.quantity,
			sellableUnitRequirements = configuration.requirements
				.sortedBy(SellableUnitRequirement::sellableUnitId)
				.toList(),
		)
	}

	private fun requireNonNegative(value: Long, name: String) {
		if (value < 0) {
			fail(FailureCode.INVALID_REQUEST, "$name must not be negative")
		}
	}

	private fun fail(code: FailureCode, message: String): Nothing = throw DomainFailure(code, message)
}
