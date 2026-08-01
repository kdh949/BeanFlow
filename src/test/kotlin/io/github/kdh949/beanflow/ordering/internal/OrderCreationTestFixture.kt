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
                operations_audit_record,
                operations_reprocessing_case,
                payment_refund_point_recovery_work,
                payment_order_point_accrual_outcome,
                payment_reconciliation,
                payment_idempotency_record,
                payment_payment,
                payment_method,
                ordering_idempotency_record,
                ordering_order_line,
                ordering_order,
                loyalty_point_accrual_result,
                loyalty_point_recovery_result,
                loyalty_point_recovery_pending,
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

    fun insertPoints(
        jdbcTemplate: JdbcTemplate,
        customerId: UUID,
        amountKrw: Long,
    ): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw
            )
            VALUES (?, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customerId,
            amountKrw,
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                issuer_type, issuer_reference
            )
            VALUES (?, ?, ?, 0, ?, 'PLATFORM', 'platform:test-fixture')
            """.trimIndent(),
            lotId,
            accountId,
            amountKrw,
            Timestamp.from(Instant.parse("2035-01-01T00:00:00Z")),
        )
        return accountId to lotId
    }

    fun insertFixedCoupon(
        jdbcTemplate: JdbcTemplate,
        fixture: OrderCreationFixture,
        discountKrw: Long,
    ): UUID {
        val campaignId = UUID.randomUUID()
        val issuanceId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO promotion_campaign (
                id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible
            )
            VALUES (?, ?, true, 'FIXED_KRW', ?, NULL, 0, NULL, true)
            """.trimIndent(),
            campaignId,
            fixture.storeId,
            discountKrw,
        )
        jdbcTemplate.update(
            """
            INSERT INTO promotion_coupon_issuance (
                id, campaign_id, customer_id, state, coupon_expires_at, reserved_order_id
            )
            VALUES (?, ?, ?, 'AVAILABLE', ?, NULL)
            """.trimIndent(),
            issuanceId,
            campaignId,
            fixture.customerId,
            Timestamp.from(Instant.parse("2035-01-01T00:00:00Z")),
        )
        return issuanceId
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

    fun count(
        jdbcTemplate: JdbcTemplate,
        table: String,
    ): Long = requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))
}
