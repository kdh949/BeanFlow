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
    fun registerPublicReference(
        jdbcTemplate: JdbcTemplate,
        orderId: UUID,
        allocatedAt: Instant = Instant.parse("2026-08-12T00:00:00Z"),
    ): String {
        val reference = registeredReference(orderId)
        jdbcTemplate.update(
            "INSERT INTO ordering_public_reference_registry (public_reference, allocated_at) VALUES (?, ?)",
            reference,
            Timestamp.from(allocatedAt),
        )
        return reference
    }

    fun registeredReference(orderId: UUID): String {
        val body =
            orderId
                .toString()
                .replace("-", "")
                .take(8)
                .uppercase()
                .map { character ->
                    when (character) {
                        '0' -> '2'
                        '1' -> '3'
                        else -> character
                    }
                }.joinToString("")
        return "BF-${body.take(4)}-${body.takeLast(4)}"
    }

    fun pickupSequence(orderId: UUID): Long = (orderId.hashCode().toLong() and Int.MAX_VALUE.toLong()) + 1

    fun clean(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                event_publication,
                notification_delivery,
                operations_customer_cancellation_refund_reconciliation_command,
                operations_audit_record,
                operations_reprocessing_case,
                operations_order_compensation_step,
                operations_order_compensation_benefit_policy_snapshot,
                operations_order_compensation_case,
                payment_refund_point_recovery_work,
                payment_order_point_accrual_outcome,
                payment_cancellation_recovery_snapshot,
                payment_refund_point_allocation,
                payment_refund_line_allocation,
                payment_refund,
                payment_reconciliation,
                payment_idempotency_record,
                payment_payment,
                payment_method,
                ordering_idempotency_record,
                ordering_cancellation_command_idempotency,
                ordering_acceptance_timeout_work,
                ordering_order_settlement_input_snapshot,
                ordering_order_line,
                ordering_order,
                ordering_pickup_counter,
                ordering_public_reference_registry,
                identity_store_membership,
                identity_merchant_account,
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
                merchant_store_settlement_terms,
                merchant_store_discovery_profile,
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
                minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                cost_bearer, platform_share_bps, store_share_bps
            )
            VALUES (?, ?, true, 'FIXED_KRW', ?, NULL, 0, NULL, true, 'STORE', 0, 10000)
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
        includeSettlementTerms: Boolean = true,
        includeDisplayProfile: Boolean = true,
        settlementFeeRateBps: Int = 500,
        settlementTermsEffectiveTo: Instant? = null,
    ) {
        jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
            fixture.storeId,
        )
        if (includeDisplayProfile) {
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
                VALUES (?, 'BeanFlow Test Store', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '1168010100')
                """.trimIndent(),
                fixture.storeId,
            )
        }
        if (includeSettlementTerms) {
            jdbcTemplate.update(
                """
                INSERT INTO merchant_store_settlement_terms (
                    terms_version_id, store_id, source_reference, fee_rate_bps,
                    effective_from, effective_to, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                fixture.storeId,
                "test:store-settlement-terms:${fixture.storeId}",
                settlementFeeRateBps,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                settlementTermsEffectiveTo?.let(Timestamp::from),
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
            )
        }
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

    fun insertSettlementInputForDirectOrder(
        jdbcTemplate: JdbcTemplate,
        orderId: UUID,
        storeId: UUID,
        grossPaidKrw: Long,
        createdAt: Instant,
    ) {
        val termsVersionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store (id, accepting_orders, pickup_enabled)
            VALUES (?, true, true)
            """.trimIndent(),
            storeId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store_settlement_terms (
                terms_version_id, store_id, source_reference, fee_rate_bps,
                effective_from, effective_to, created_at
            ) VALUES (?, ?, ?, 0, ?, NULL, ?)
            """.trimIndent(),
            termsVersionId,
            storeId,
            "test:direct-order-terms:$orderId",
            Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
            Timestamp.from(createdAt),
        )
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_settlement_input_snapshot (
                order_id, store_id, store_settlement_terms_version_id,
                store_settlement_terms_source_reference,
                coupon_discount_krw, platform_coupon_cost_krw, coupon_cost_krw,
                points_applied_krw, point_cost_krw,
                gross_paid_krw, fee_base_krw, fee_rate_bps, fee_krw,
                benefit_cost_krw, net_settlement_krw, currency,
                snapshot_schema_version, canonical_snapshot_hash, created_at
            ) VALUES (
                ?, ?, ?, ?, 0, 0, 0, 0, 0, ?, ?, 0, 0, 0, ?, 'KRW', 1, ?, ?
            )
            """.trimIndent(),
            orderId,
            storeId,
            termsVersionId,
            "test:direct-order-terms:$orderId",
            grossPaidKrw,
            grossPaidKrw,
            grossPaidKrw,
            "f".repeat(64),
            Timestamp.from(createdAt),
        )
    }
}
