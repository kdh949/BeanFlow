package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.merchant.internal.domain.MenuConfigurationDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuOptionDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuQuoteCalculator
import io.github.kdh949.beanflow.merchant.internal.domain.StoreDefinition
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JpaMenuQuoteService(
	private val storeRepository: StoreJpaRepository,
	private val menuRepository: MenuJpaRepository,
	private val optionRepository: MenuOptionJpaRepository,
	private val configurationRepository: MenuConfigurationJpaRepository,
	private val requirementRepository: MenuConfigurationRequirementJpaRepository,
) : MenuQuoteUseCase {

	private val calculator = MenuQuoteCalculator()

	override fun quote(storeId: UUID, lines: List<QuoteOrderLine>): List<MenuLineQuote> {
		val (store, menus) = loadDefinitions(storeId, lines)
		return calculator.quote(store = store, menus = menus, lines = lines)
	}

	override fun quoteCurrentBatch(
		storeId: UUID,
		lines: List<QuoteOrderLine>,
	): List<CurrentMenuLineQuoteResult> {
		val (store, menus) = loadDefinitions(storeId, lines)
		return calculator.quoteCurrentBatch(store = store, menus = menus, lines = lines)
	}

	private fun loadDefinitions(
		storeId: UUID,
		lines: List<QuoteOrderLine>,
	): Pair<StoreDefinition, Map<UUID, MenuDefinition>> {
		val store =
			storeRepository.findById(storeId).orElse(null)
				?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
		val requestedMenuIds = lines.map(QuoteOrderLine::menuId).toSet()
		val menus = menuRepository.findAllById(requestedMenuIds).associate { menu ->
			val options = optionRepository.findAllByMenuId(menu.id)
			val configurations = configurationRepository.findAllByMenuId(menu.id).map { configuration ->
				MenuConfigurationDefinition(
					optionIds = configuration.normalizedOptionKey
						.takeIf(String::isNotBlank)
						?.split(",")
						?.map(UUID::fromString)
						?.toSet()
						.orEmpty(),
					available = configuration.available,
					requirements = requirementRepository
						.findAllByMenuConfigurationId(configuration.id)
						.map {
							SellableUnitRequirement(it.sellableUnitId, it.quantityPerLineUnit)
						},
				)
			}
			menu.id to MenuDefinition(
				id = menu.id,
				storeId = menu.storeId,
				name = menu.name,
				basePriceKrw = menu.basePriceKrw,
				available = menu.available,
				options = options.map {
					MenuOptionDefinition(it.id, it.name, it.additionalPriceKrw, it.available)
				},
				configurations = configurations,
			)
		}
		return StoreDefinition(store.id, store.acceptingOrders, store.pickupEnabled) to menus
	}
}
