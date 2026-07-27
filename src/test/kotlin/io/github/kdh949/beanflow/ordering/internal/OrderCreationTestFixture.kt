package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class OrderCreationFixture(
	val customerId: UUID = UUID.randomUUID(),
	val storeId: UUID = UUID.randomUUID(),
	val menuId: UUID = UUID.randomUUID(),
	val pickupSlotId: UUID = UUID.randomUUID(),
	val sellableUnitId: UUID = UUID.randomUUID(),
) {
	fun command(
		pointsToUseKrw: Long = 0,
		couponIssuanceId: UUID? = null,
		quantity: Long = 1,
	): CreateOrderCommand =
		CreateOrderCommand(
			customerId = customerId,
			storeId = storeId,
			pickupSlotId = pickupSlotId,
			lines = listOf(CreateOrderLineCommand(menuId, emptyList(), quantity)),
			couponIssuanceId = couponIssuanceId,
			pointsToUseKrw = pointsToUseKrw,
		)
}

internal object OrderCreationDatabaseFixture {

	fun clean(jdbcTemplate: JdbcTemplate) {
		jdbcTemplate.execute(
			"""
			TRUNCATE TABLE
			    ordering_idempotency_record,
			    ordering_order_line,
			    ordering_order,
			    loyalty_point_transaction,
			    loyalty_point_reservation_allocation,
			    loyalty_point_reservation,
			    loyalty_point_lot,
			    loyalty_point_account,
			    promotion_coupon_reservation,
			    promotion_coupon_issuance,
			    promotion_campaign_eligible_menu,
			    promotion_campaign,
			    inventory_stock_reservation,
			    inventory_sellable_stock,
			    fulfillment_pickup_reservation,
			    fulfillment_pickup_slot,
			    merchant_menu_configuration_requirement,
			    merchant_menu_configuration,
			    merchant_menu_option,
			    merchant_menu,
			    merchant_store
			CASCADE
			""".trimIndent(),
		)
	}

	fun insertBase(
		jdbcTemplate: JdbcTemplate,
		fixture: OrderCreationFixture,
		slotCapacity: Long = 10,
		stockAvailable: Long = 10,
		priceKrw: Long = 1_000,
	) {
		jdbcTemplate.update(
			"INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
			fixture.storeId,
		)
		jdbcTemplate.update(
			"""
			INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available)
			VALUES (?, ?, 'Americano', ?, true)
			""".trimIndent(),
			fixture.menuId,
			fixture.storeId,
			priceKrw,
		)
		val configurationId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			INSERT INTO merchant_menu_configuration (id, menu_id, normalized_option_key, available)
			VALUES (?, ?, '', true)
			""".trimIndent(),
			configurationId,
			fixture.menuId,
		)
		jdbcTemplate.update(
			"""
			INSERT INTO merchant_menu_configuration_requirement (
			    id, menu_configuration_id, sellable_unit_id, quantity_per_line_unit
			)
			VALUES (?, ?, ?, 1)
			""".trimIndent(),
			UUID.randomUUID(),
			configurationId,
			fixture.sellableUnitId,
		)
		jdbcTemplate.update(
			"""
			INSERT INTO fulfillment_pickup_slot (
			    id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count
			)
			VALUES (?, ?, ?, ?, ?, 0, 0)
			""".trimIndent(),
			fixture.pickupSlotId,
			fixture.storeId,
			Timestamp.from(Instant.parse("2030-01-01T00:10:00Z")),
			Timestamp.from(Instant.parse("2030-01-01T00:20:00Z")),
			slotCapacity,
		)
		jdbcTemplate.update(
			"""
			INSERT INTO inventory_sellable_stock (
			    id, store_id, available_quantity, reserved_quantity, confirmed_quantity
			)
			VALUES (?, ?, ?, 0, 0)
			""".trimIndent(),
			fixture.sellableUnitId,
			fixture.storeId,
			stockAvailable,
		)
	}

	fun count(jdbcTemplate: JdbcTemplate, table: String): Long =
		requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))
}
