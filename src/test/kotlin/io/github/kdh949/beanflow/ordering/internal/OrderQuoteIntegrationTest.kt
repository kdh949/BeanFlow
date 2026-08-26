package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies non-reserving order quote and terminal stale replay")
@SpringBootTest
internal class OrderQuoteIntegrationTest
    @Autowired
    constructor(
        private val quotes: OrderQuoteUseCase,
        private val orders: CreateOrderUseCase,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `quote returns an authoritative fingerprint without reserving or persisting`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val first = quotes.quote(fixture.quoteCommand())
            val second = quotes.quote(fixture.quoteCommand())

            assertThat(first.quoteFingerprint).matches("[0-9a-f]{64}")
            assertThat(second.quoteFingerprint).isEqualTo(first.quoteFingerprint)
            assertThat(first.guarantee).isEqualTo("NONE")
            assertThat(first.store.name).isEqualTo("BeanFlow Test Store")
            assertThat(first.lines.single().lineTotalKrw).isEqualTo(1_000)
            assertThat(first.pricing.payableKrw).isEqualTo(1_000)
            assertNoTransactionWrites()
        }

        @Test
        fun `display and image changes do not invalidate a transactional quote`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val original = quotes.quote(fixture.quoteCommand())

            jdbcTemplate.update(
                """
                UPDATE merchant_menu
                   SET display_category = 'SEASONAL',
                       public_description = 'Customer-facing copy',
                       image_original_key = 'menu/original',
                       image_thumbnail_key = 'menu/thumbnail',
                       image_sha256 = repeat('a', 64),
                       image_updated_at = '2026-01-01T00:00:00Z',
                       version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                fixture.menuId,
            )
            jdbcTemplate.update(
                """
                UPDATE merchant_store
                   SET image_original_key = 'store/original',
                       image_thumbnail_key = 'store/thumbnail',
                       image_sha256 = repeat('b', 64),
                       image_updated_at = '2026-01-01T00:00:00Z',
                       version = version + 1
                 WHERE id = ?
                """.trimIndent(),
                fixture.storeId,
            )

            val changed = quotes.quote(fixture.quoteCommand())

            assertThat(changed.quoteFingerprint).isEqualTo(original.quoteFingerprint)
            assertThat(changed.pricing).isEqualTo(original.pricing)
            assertThat(changed.lines).isEqualTo(original.lines)
            assertNoTransactionWrites()
        }

        @Test
        fun `exact quote creates an order with the same immutable money and display snapshot`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val quote = quotes.quote(fixture.quoteCommand())

            val response =
                orders.create(
                    "quoted-order-001",
                    fixture.command(expectedQuoteFingerprint = quote.quoteFingerprint),
                )

            assertThat(response.status).isEqualTo(201)
            assertThat(response.body).contains("\"storeName\":\"${quote.store.name}\"")
            assertThat(response.body).contains("\"subtotalKrw\":${quote.pricing.subtotalKrw}")
            assertThat(response.body).contains("\"payableKrw\":${quote.pricing.payableKrw}")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isOne()
        }

        @Test
        fun `changed owner state stores and replays one terminal stale response`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val quote = quotes.quote(fixture.quoteCommand())
            jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", fixture.menuId)
            val staleCommand = fixture.command(expectedQuoteFingerprint = quote.quoteFingerprint)

            val first = orders.create("stale-order-001", staleCommand)
            val replay = orders.create("stale-order-001", staleCommand)

            assertThat(first.status).isEqualTo(409)
            assertThat(first.body).contains("\"code\":\"ORDER_QUOTE_STALE\"")
            assertThat(first.body).contains("\"currentQuote\"")
            assertThat(first.body).contains("\"payableKrw\":1200")
            assertThat(replay.body).isEqualTo(first.body)
            assertThat(replay.replay).isTrue()
            assertNoTransactionWrites()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT status FROM ordering_idempotency_record",
                    String::class.java,
                ),
            ).isEqualTo("FAILED")
        }

        @Test
        fun `reconfirmed quote requires a new key and then succeeds`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val original = quotes.quote(fixture.quoteCommand())
            jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", fixture.menuId)
            orders.create("stale-order-002", fixture.command(expectedQuoteFingerprint = original.quoteFingerprint))
            val current = quotes.quote(fixture.quoteCommand())

            val reused =
                orders.create(
                    "stale-order-002",
                    fixture.command(expectedQuoteFingerprint = current.quoteFingerprint),
                )
            val accepted =
                orders.create(
                    "reconfirmed-order-001",
                    fixture.command(expectedQuoteFingerprint = current.quoteFingerprint),
                )

            assertThat(reused.status).isEqualTo(409)
            assertThat(reused.body).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"")
            assertThat(accepted.status).isEqualTo(201)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
        }

        @Test
        fun `malformed fingerprint is rejected before idempotency registration`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

            val response = orders.create("malformed-quote-01", fixture.command(expectedQuoteFingerprint = "not-a-hash"))

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body).contains("\"code\":\"INVALID_REQUEST\"")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isZero()
            assertNoTransactionWrites()
        }

        @Test
        fun `same payable with different point issuer provenance changes the fingerprint`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val (accountId, lotId) = OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, fixture.customerId, 500)
            val command = fixture.quoteCommand(pointsToUseKrw = 500)
            val original = quotes.quote(command)

            jdbcTemplate.update("DELETE FROM loyalty_point_lot WHERE id = ?", lotId)
            jdbcTemplate.update("DELETE FROM loyalty_point_account WHERE id = ?", accountId)
            val replacementAccountId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO loyalty_point_account (id, customer_id, available_points_krw, reserved_points_krw) VALUES (?, ?, 500, 0)",
                replacementAccountId,
                fixture.customerId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (
                    id, point_account_id, available_amount_krw, reserved_amount_krw, expires_at,
                    issuer_type, issuer_reference
                ) VALUES (?, ?, 500, 0, '2035-01-01T00:00:00Z', 'STORE', ?)
                """.trimIndent(),
                UUID.randomUUID(),
                replacementAccountId,
                "store:${fixture.storeId}",
            )
            val changed = quotes.quote(command)
            val stale =
                orders.create(
                    "point-provenance-stale-001",
                    fixture.command(500, expectedQuoteFingerprint = original.quoteFingerprint),
                )

            assertThat(changed.pricing.payableKrw).isEqualTo(original.pricing.payableKrw)
            assertThat(changed.quoteFingerprint).isNotEqualTo(original.quoteFingerprint)
            assertThat(stale.status).isEqualTo(409)
            assertThat(stale.body).contains("\"code\":\"ORDER_QUOTE_STALE\"")
            assertNoTransactionWrites()
        }

        @Test
        fun `pickup capacity change invalidates the quoted slot result`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val original = quotes.quote(fixture.quoteCommand())
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET capacity = capacity + 1 WHERE id = ?",
                fixture.pickupSlotId,
            )

            val response =
                orders.create(
                    "pickup-capacity-stale-001",
                    fixture.command(expectedQuoteFingerprint = original.quoteFingerprint),
                )

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"code\":\"ORDER_QUOTE_STALE\"")
            assertNoTransactionWrites()
        }

        @Test
        fun `coupon term change invalidates the quoted discount`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val issuanceId = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 100)
            val original = quotes.quote(fixture.quoteCommand(couponIssuanceId = issuanceId))
            jdbcTemplate.update(
                "UPDATE promotion_campaign SET fixed_amount_krw = 200 WHERE id = " +
                    "(SELECT campaign_id FROM promotion_coupon_issuance WHERE id = ?)",
                issuanceId,
            )

            val response =
                orders.create(
                    "coupon-terms-stale-001",
                    fixture.command(couponIssuanceId = issuanceId, expectedQuoteFingerprint = original.quoteFingerprint),
                )

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"couponDiscountKrw\":200")
            assertNoTransactionWrites()
        }

        @Test
        fun `selected option change invalidates the quoted line snapshot`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val optionId = insertOptionConfiguration(fixture, additionalPriceKrw = 500)
            val original = quotes.quote(fixture.quoteCommand(optionIds = listOf(optionId)))
            jdbcTemplate.update(
                "UPDATE merchant_menu_option SET name = 'Oat Milk', additional_price_krw = 700 WHERE id = ?",
                optionId,
            )
            val command =
                fixture
                    .command(expectedQuoteFingerprint = original.quoteFingerprint)
                    .copy(lines = fixture.command().lines.map { it.copy(optionIds = listOf(optionId)) })

            val response = orders.create("option-snapshot-stale-001", command)

            assertThat(response.status).isEqualTo(409)
            assertThat(response.body).contains("\"optionNames\":[\"Oat Milk\"]")
            assertThat(response.body).contains("\"lineTotalKrw\":1700")
            assertNoTransactionWrites()
        }

        private fun OrderCreationFixture.quoteCommand(
            pointsToUseKrw: Long = 0,
            couponIssuanceId: UUID? = null,
            quantity: Long = 1,
            optionIds: List<UUID> = emptyList(),
        ): OrderQuoteCommand =
            OrderQuoteCommand(
                customerId = customerId,
                storeId = storeId,
                pickupSlotId = pickupSlotId,
                lines = command(pointsToUseKrw, couponIssuanceId, quantity).lines.map { it.copy(optionIds = optionIds) },
                couponIssuanceId = couponIssuanceId,
                pointsToUseKrw = pointsToUseKrw,
            )

        private fun insertOptionConfiguration(
            fixture: OrderCreationFixture,
            additionalPriceKrw: Long,
        ): UUID {
            val optionId = UUID.randomUUID()
            val configurationId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available) " +
                    "VALUES (?, ?, 'Soy Milk', ?, true)",
                optionId,
                fixture.menuId,
                additionalPriceKrw,
            )
            jdbcTemplate.update(
                "INSERT INTO merchant_menu_configuration (id, menu_id, normalized_option_key, available) " +
                    "VALUES (?, ?, ?, true)",
                configurationId,
                fixture.menuId,
                optionId.toString(),
            )
            jdbcTemplate.update(
                "INSERT INTO merchant_menu_configuration_requirement " +
                    "(id, menu_configuration_id, sellable_unit_id, quantity_per_line_unit) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(),
                configurationId,
                fixture.sellableUnitId,
            )
            return optionId
        }

        private fun assertNoTransactionWrites() {
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "promotion_coupon_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "loyalty_point_reservation")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "payment_payment")).isZero()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "operations_audit_record")).isZero()
        }
    }
