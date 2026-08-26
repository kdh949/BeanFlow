package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.MenuQuoteUseCase
import io.github.kdh949.beanflow.merchant.api.MerchantMenuQuoteMaterial
import io.github.kdh949.beanflow.merchant.api.MerchantOrderQuoteOperations
import io.github.kdh949.beanflow.merchant.api.MerchantOrderQuoteSnapshot
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
) : MenuQuoteUseCase,
    MerchantOrderQuoteOperations {
    private val calculator = MenuQuoteCalculator()

    override fun quote(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): List<MenuLineQuote> {
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

    override fun quoteForOrder(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): MerchantOrderQuoteSnapshot {
        val loaded = load(storeId, lines)
        val quotes = calculator.quote(store = loaded.storeDefinition, menus = loaded.menuDefinitions, lines = lines)
        val configurationsByMenu = loaded.configurations.groupBy(MenuConfigurationEntity::menuId)
        val materials =
            lines.map { line ->
                val menu = loaded.menus.getValue(line.menuId)
                val optionKey =
                    line.optionIds
                        .distinct()
                        .sortedBy(UUID::toString)
                        .joinToString(",")
                val configuration =
                    configurationsByMenu[line.menuId].orEmpty().singleOrNull { it.normalizedOptionKey == optionKey }
                        ?: throw DomainFailure(
                            FailureCode.DEPENDENCY_UNAVAILABLE,
                            "Merchant menu configuration snapshot is missing",
                        )
                MerchantMenuQuoteMaterial(
                    menuId = menu.id,
                    menuVersion = menu.version,
                    configurationId = configuration.id,
                    configurationVersion = configuration.version,
                )
            }
        return MerchantOrderQuoteSnapshot(
            storeVersion = loaded.storeEntity.version,
            lines = quotes,
            materials = materials,
        )
    }

    private fun loadDefinitions(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): Pair<StoreDefinition, Map<UUID, MenuDefinition>> {
        val loaded = load(storeId, lines)
        return loaded.storeDefinition to loaded.menuDefinitions
    }

    private fun load(
        storeId: UUID,
        lines: List<QuoteOrderLine>,
    ): LoadedMenuDefinitions {
        val store =
            storeRepository.findById(storeId).orElse(null)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store was not found")
        val requestedMenuIds = lines.map(QuoteOrderLine::menuId).toSet()
        val menuEntities = menuRepository.findAllById(requestedMenuIds)
        val optionsByMenu = optionRepository.findAllByMenuIdIn(requestedMenuIds).groupBy(MenuOptionEntity::menuId)
        val configurations = configurationRepository.findAllByMenuIdIn(requestedMenuIds)
        val configurationsByMenu = configurations.groupBy(MenuConfigurationEntity::menuId)
        val requirementsByConfiguration =
            requirementRepository
                .findAllByMenuConfigurationIdIn(configurations.map(MenuConfigurationEntity::id))
                .groupBy(MenuConfigurationRequirementEntity::menuConfigurationId)
        val menus =
            menuEntities.associate { menu ->
                val configurationsForMenu =
                    configurationsByMenu[menu.id].orEmpty().map { configuration ->
                        MenuConfigurationDefinition(
                            optionIds = parseNormalizedOptionKey(configuration.normalizedOptionKey),
                            available = configuration.available,
                            requirements =
                                requirementsByConfiguration[configuration.id]
                                    .orEmpty()
                                    .map {
                                        SellableUnitRequirement(it.sellableUnitId, it.quantityPerLineUnit)
                                    },
                        )
                    }
                menu.id to
                    MenuDefinition(
                        id = menu.id,
                        storeId = menu.storeId,
                        name = menu.name,
                        basePriceKrw = menu.basePriceKrw,
                        available = menu.available,
                        options =
                            optionsByMenu[menu.id].orEmpty().map {
                                MenuOptionDefinition(it.id, it.name, it.additionalPriceKrw, it.available)
                            },
                        configurations = configurationsForMenu,
                    )
            }
        return LoadedMenuDefinitions(
            storeEntity = store,
            storeDefinition = StoreDefinition(store.id, store.acceptingOrders, store.pickupEnabled),
            menus = menuEntities.associateBy(MenuEntity::id),
            menuDefinitions = menus,
            configurations = configurations,
        )
    }

    private fun parseNormalizedOptionKey(key: String): Set<UUID> {
        if (key.isEmpty()) return emptySet()
        val parsed =
            try {
                key.split(",").map(UUID::fromString)
            } catch (_: IllegalArgumentException) {
                corruptConfiguration()
            }
        val canonical = parsed.distinct().sortedBy(UUID::toString).joinToString(",")
        if (canonical != key) corruptConfiguration()
        return parsed.toSet()
    }

    private fun corruptConfiguration(): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Merchant menu configuration data is unavailable",
        )

    private data class LoadedMenuDefinitions(
        val storeEntity: StoreEntity,
        val storeDefinition: StoreDefinition,
        val menus: Map<UUID, MenuEntity>,
        val menuDefinitions: Map<UUID, MenuDefinition>,
        val configurations: List<MenuConfigurationEntity>,
    )
}
