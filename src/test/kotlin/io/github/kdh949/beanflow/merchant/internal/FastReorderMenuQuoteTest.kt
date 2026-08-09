package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.CurrentMenuLineQuoteResult
import io.github.kdh949.beanflow.merchant.api.MenuItemUnavailability
import io.github.kdh949.beanflow.merchant.api.MenuItemUnavailableReason
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.merchant.internal.domain.MenuConfigurationDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuOptionDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuQuoteCalculator
import io.github.kdh949.beanflow.merchant.internal.domain.StoreDefinition
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class FastReorderMenuQuoteTest {
    private val store = StoreDefinition(UUID.randomUUID(), acceptingOrders = true, pickupEnabled = true)
    private val calculator = MenuQuoteCalculator()

    @Test
    fun `batch quote returns stable removed unavailable and configuration reasons in request order`() {
        val removedMenuId = UUID.randomUUID()
        val unavailableMenu = menu(available = false)
        val missingOption = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val unavailableOption = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val optionMenu =
            menu(
                options = listOf(MenuOptionDefinition(unavailableOption, "Unavailable", 0, false)),
                configurations = emptyList(),
            )
        val configurationMenu = menu(configurations = emptyList())
        val lines =
            listOf(
                QuoteOrderLine(removedMenuId, emptyList(), 1),
                QuoteOrderLine(unavailableMenu.id, emptyList(), 1),
                QuoteOrderLine(optionMenu.id, listOf(unavailableOption, missingOption), 1),
                QuoteOrderLine(configurationMenu.id, emptyList(), 1),
            )

        val result =
            calculator.quoteCurrentBatch(
                store,
                listOf(unavailableMenu, optionMenu, configurationMenu).associateBy(MenuDefinition::id),
                lines,
            )

        assertThat(result).containsExactly(
            unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_REMOVED)),
            unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_NOT_AVAILABLE)),
            unavailable(
                MenuItemUnavailability(MenuItemUnavailableReason.OPTION_REMOVED, missingOption),
                MenuItemUnavailability(MenuItemUnavailableReason.OPTION_NOT_AVAILABLE, unavailableOption),
            ),
            unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_CONFIGURATION_NOT_AVAILABLE)),
        )
    }

    @Test
    fun `menu level failure suppresses option and configuration failures`() {
        val optionId = UUID.randomUUID()
        val menu = menu(available = false, options = emptyList(), configurations = emptyList())

        val result =
            calculator.quoteCurrentBatch(
                store,
                mapOf(menu.id to menu),
                listOf(QuoteOrderLine(menu.id, listOf(optionId), 1)),
            )

        assertThat(result).containsExactly(
            unavailable(MenuItemUnavailability(MenuItemUnavailableReason.MENU_NOT_AVAILABLE)),
        )
    }

    @Test
    fun `available batch quote reuses normalized current price and requirement calculation`() {
        val optionId = UUID.randomUUID()
        val sellableUnitId = UUID.randomUUID()
        val menu =
            menu(
                basePriceKrw = 4_000,
                options = listOf(MenuOptionDefinition(optionId, "Shot", 500, true)),
                configurations =
                    listOf(
                        MenuConfigurationDefinition(
                            setOf(optionId),
                            available = true,
                            requirements = listOf(SellableUnitRequirement(sellableUnitId, 2)),
                        ),
                    ),
            )

        val result =
            calculator.quoteCurrentBatch(
                store,
                mapOf(menu.id to menu),
                listOf(QuoteOrderLine(menu.id, listOf(optionId), 3)),
            )

        val quote = (result.single() as CurrentMenuLineQuoteResult.Available).quote
        assertThat(quote.unitPriceKrw).isEqualTo(4_500)
        assertThat(quote.optionSnapshots.map { it.optionId }).containsExactly(optionId)
        assertThat(quote.sellableUnitRequirements).containsExactly(SellableUnitRequirement(sellableUnitId, 2))
    }

    @Test
    fun `store and corrupted owner setup remain top level failures`() {
        val menu =
            menu(
                configurations =
                    listOf(MenuConfigurationDefinition(emptySet(), available = true, requirements = emptyList())),
            )

        assertThatThrownBy {
            calculator.quoteCurrentBatch(
                store.copy(acceptingOrders = false),
                mapOf(menu.id to menu),
                listOf(QuoteOrderLine(menu.id, emptyList(), 1)),
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.MENU_CONFIGURATION_NOT_AVAILABLE)
        }
        assertThatThrownBy {
            calculator.quoteCurrentBatch(
                store,
                mapOf(menu.id to menu),
                listOf(QuoteOrderLine(menu.id, emptyList(), 1)),
            )
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    private fun menu(
        available: Boolean = true,
        basePriceKrw: Long = 1_000,
        options: List<MenuOptionDefinition> = emptyList(),
        configurations: List<MenuConfigurationDefinition> =
            listOf(
                MenuConfigurationDefinition(
                    emptySet(),
                    available = true,
                    requirements = listOf(SellableUnitRequirement(UUID.randomUUID(), 1)),
                ),
            ),
    ): MenuDefinition =
        MenuDefinition(
            id = UUID.randomUUID(),
            storeId = store.id,
            name = "Menu",
            basePriceKrw = basePriceKrw,
            available = available,
            options = options,
            configurations = configurations,
        )

    private fun unavailable(vararg failures: MenuItemUnavailability): CurrentMenuLineQuoteResult.Unavailable =
        CurrentMenuLineQuoteResult.Unavailable(failures.toList())
}
