package io.github.kdh949.beanflow.ordering

import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.OptionSnapshot
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.merchant.internal.domain.MenuConfigurationDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuOptionDefinition
import io.github.kdh949.beanflow.merchant.internal.domain.MenuQuoteCalculator
import io.github.kdh949.beanflow.merchant.internal.domain.StoreDefinition
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderTest {

	@Test
	fun `order snapshots menu option price and requirements`() {
		val fixture = menuFixture()
		val mutableOptions = fixture.menu.options.toMutableList()
		val quote = MenuQuoteCalculator().quote(
			store = fixture.store,
			menus = mapOf(fixture.menu.id to fixture.menu.copy(options = mutableOptions)),
			lines = listOf(QuoteOrderLine(fixture.menu.id, listOf(fixture.option.id), 2)),
		).single()
		val pricing = OrderPricingCalculator().calculate(
			lines = listOf(PricingLine(0, quote.menuId, Krw.of(quote.unitPriceKrw), quote.quantity, true)),
			couponDiscount = Krw.of(500),
			pointsToUse = Krw.of(500),
		)
		val createdAt = Instant.parse("2026-07-28T00:00:00Z")

		val order = Order.pendingPayment(
			id = UUID.randomUUID(),
			customerId = UUID.randomUUID(),
			storeId = fixture.store.id,
			pickupSlotId = UUID.randomUUID(),
			lineIds = listOf(UUID.randomUUID()),
			quotes = listOf(quote),
			pricing = pricing,
			createdAt = createdAt,
		)
		mutableOptions.clear()

		assertThat(order.lines.single().menuName).isEqualTo("Americano")
		assertThat(order.lines.single().options.map(OptionSnapshot::name)).containsExactly("Extra shot")
		assertThat(order.lines.single().unitPriceKrw).isEqualTo(4_500)
		assertThat(order.lines.single().sellableUnitRequirements)
			.containsExactly(SellableUnitRequirement(fixture.sellableUnitId, 1))
		assertThat(order.reservationExpiresAt).isEqualTo(createdAt.plusSeconds(300))
	}

	@Test
	fun `option owned by another menu fails as invalid request`() {
		val fixture = menuFixture()

		assertThatThrownBy {
			MenuQuoteCalculator().quote(
				store = fixture.store,
				menus = mapOf(fixture.menu.id to fixture.menu),
				lines = listOf(QuoteOrderLine(fixture.menu.id, listOf(UUID.randomUUID()), 1)),
			)
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
			}
	}

	private fun menuFixture(): MenuFixture {
		val storeId = UUID.randomUUID()
		val menuId = UUID.randomUUID()
		val option = MenuOptionDefinition(UUID.randomUUID(), "Extra shot", 500, true)
		val sellableUnitId = UUID.randomUUID()
		return MenuFixture(
			store = StoreDefinition(storeId, acceptingOrders = true, pickupEnabled = true),
			option = option,
			sellableUnitId = sellableUnitId,
			menu = MenuDefinition(
				id = menuId,
				storeId = storeId,
				name = "Americano",
				basePriceKrw = 4_000,
				available = true,
				options = listOf(option),
				configurations = listOf(
					MenuConfigurationDefinition(
						optionIds = setOf(option.id),
						available = true,
						requirements = listOf(SellableUnitRequirement(sellableUnitId, 1)),
					),
				),
			),
		)
	}

	private data class MenuFixture(
		val store: StoreDefinition,
		val menu: MenuDefinition,
		val option: MenuOptionDefinition,
		val sellableUnitId: UUID,
	)
}
