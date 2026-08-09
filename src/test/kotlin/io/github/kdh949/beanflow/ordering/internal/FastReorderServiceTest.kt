package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class FastReorderServiceTest @Autowired constructor(
    private val createOrder: CreateOrderUseCase,
    private val reorderOrder: ReorderOrderUseCase,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    @BeforeEach
    fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

    @Test
    fun `terminal source creates a new pending order with current price comparison and exact replay`() {
        val source = sourceOrder()
        jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", source.fixture.menuId)

        val first = reorderOrder.reorder("reorder-success-01", source.command())
        val replay = reorderOrder.reorder("reorder-success-01", source.command())

        assertThat(first.status).isEqualTo(201)
        assertThat(first.body).contains(
            "\"state\":\"PENDING_PAYMENT\"",
            "\"hasPriceChanges\":true",
            "\"sourceSubtotalKrw\":1000",
            "\"currentSubtotalKrw\":1200",
            "\"subtotalDifferenceKrw\":200",
            "\"lineDifferenceKrw\":200",
        )
        assertThat(replay.status).isEqualTo(first.status)
        assertThat(replay.body).isEqualTo(first.body)
        assertThat(replay.replay).isTrue()
        assertThat(count("ordering_order")).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT operation FROM ordering_idempotency_record ORDER BY operation",
                String::class.java,
            ),
        ).containsExactly("CREATE_ORDER", "REORDER_ORDER_V1")
    }

    @Test
    fun `non terminal source stores and exactly replays state conflict without a new order`() {
        val source = sourceOrder(terminal = false)

        val first = reorderOrder.reorder("reorder-state-001", source.command())
        jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", source.orderId)
        val replay = reorderOrder.reorder("reorder-state-001", source.command())

        assertThat(first.status).isEqualTo(409)
        assertThat(first.body).contains("\"code\":\"REORDER_SOURCE_STATE_INVALID\"")
        assertThat(replay.body).isEqualTo(first.body)
        assertThat(count("ordering_order")).isOne()
    }

    @Test
    fun `legacy source option selection fails closed with typed detail and no partial reservation`() {
        val source = sourceOrder()
        jdbcTemplate.update(
            "UPDATE ordering_order_line SET option_selection_snapshot_state = 'LEGACY_UNAVAILABLE', " +
                "normalized_option_ids_json = NULL WHERE order_id = ?",
            source.orderId,
        )
        val pickupBefore = count("fulfillment_pickup_reservation")
        val stockBefore = count("inventory_stock_reservation")

        val response = reorderOrder.reorder("reorder-legacy-01", source.command())

        assertThat(response.status).isEqualTo(409)
        assertThat(response.body).contains(
            "\"code\":\"REORDER_ITEMS_UNAVAILABLE\"",
            "\"reason\":\"SOURCE_OPTION_SELECTION_UNAVAILABLE\"",
            "\"lineSequence\":0",
        )
        assertThat(count("ordering_order")).isOne()
        assertThat(count("fulfillment_pickup_reservation")).isEqualTo(pickupBefore)
        assertThat(count("inventory_stock_reservation")).isEqualTo(stockBefore)
    }

    @Test
    fun `current unavailable menu fails all or nothing with owner reason`() {
        val source = sourceOrder()
        jdbcTemplate.update("UPDATE merchant_menu SET available = false WHERE id = ?", source.fixture.menuId)

        val response = reorderOrder.reorder("reorder-menu-0001", source.command())

        assertThat(response.status).isEqualTo(409)
        assertThat(response.body).contains(
            "\"code\":\"REORDER_ITEMS_UNAVAILABLE\"",
            "\"reason\":\"MENU_NOT_AVAILABLE\"",
        )
        assertThat(count("ordering_order")).isOne()
    }

    @Test
    fun `different request with the same reorder key conflicts before owner work`() {
        val source = sourceOrder()
        val first = reorderOrder.reorder("reorder-reused-01", source.command())

        val conflict =
            reorderOrder.reorder(
                "reorder-reused-01",
                source.command().copy(pickupSlotId = UUID.randomUUID()),
            )

        assertThat(first.status).isEqualTo(201)
        assertThat(conflict.status).isEqualTo(409)
        assertThat(conflict.body).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"")
        assertThat(count("ordering_order")).isEqualTo(2)
    }

    @Test
    fun `stock failure rolls back the new pickup reservation order snapshots and audit`() {
        val source = sourceOrder()
        val pickupBefore = count("fulfillment_pickup_reservation")
        val stockBefore = count("inventory_stock_reservation")
        val auditBefore = count("operations_audit_record")
        jdbcTemplate.update(
            "UPDATE inventory_sellable_stock SET available_quantity = 0 WHERE id = ?",
            source.fixture.sellableUnitId,
        )

        val response = reorderOrder.reorder("reorder-stock-fail", source.command())

        assertThat(response.status).isEqualTo(409)
        assertThat(response.body).contains("\"code\":\"STOCK_NOT_AVAILABLE\"")
        assertThat(count("ordering_order")).isOne()
        assertThat(count("fulfillment_pickup_reservation")).isEqualTo(pickupBefore)
        assertThat(count("inventory_stock_reservation")).isEqualTo(stockBefore)
        assertThat(count("operations_audit_record")).isEqualTo(auditBefore)
    }

    @Test
    fun `the same source with different keys may create multiple new orders`() {
        val source = sourceOrder()

        val first = reorderOrder.reorder("reorder-repeat-01", source.command())
        val second = reorderOrder.reorder("reorder-repeat-02", source.command())

        assertThat(first.status).isEqualTo(201)
        assertThat(second.status).isEqualTo(201)
        assertThat(count("ordering_order")).isEqualTo(3)
        assertThat(count("fulfillment_pickup_reservation")).isEqualTo(3)
        assertThat(count("inventory_stock_reservation")).isEqualTo(3)
    }

    @Test
    fun `missing and other customer source preserve ownership boundary without source details`() {
        val missing =
            reorderOrder.reorder(
                "reorder-missing-1",
                ReorderOrderCommand(
                    customerId = UUID.randomUUID(),
                    sourceOrderId = UUID.randomUUID(),
                    pickupSlotId = UUID.randomUUID(),
                    couponIssuanceId = null,
                    pointsToUseKrw = 0,
                ),
            )
        val source = sourceOrder()
        val forbidden =
            reorderOrder.reorder(
                "reorder-owner-001",
                source.command().copy(customerId = UUID.randomUUID()),
            )

        assertThat(missing.status).isEqualTo(404)
        assertThat(missing.body).contains("\"code\":\"RESOURCE_NOT_FOUND\"").doesNotContain("menuId")
        assertThat(forbidden.status).isEqualTo(403)
        assertThat(forbidden.body).contains("\"code\":\"ACCESS_DENIED\"").doesNotContain("menuId")
        assertThat(count("ordering_order")).isOne()
    }

    @Test
    fun `explicit points can create a benefit only reorder without copying old benefits`() {
        val source = sourceOrder()
        OrderCreationDatabaseFixture.insertPoints(jdbcTemplate, source.fixture.customerId, 1_000)

        val response =
            reorderOrder.reorder(
                "reorder-benefit-1",
                source.command().copy(pointsToUseKrw = 1_000),
            )

        assertThat(response.status).isEqualTo(201)
        assertThat(response.body).contains(
            "\"state\":\"PAID\"",
            "\"type\":\"BENEFIT_ONLY\"",
            "\"priceComparison\"",
        )
        assertThat(count("payment_payment")).isOne()
        assertThat(count("ordering_order")).isEqualTo(2)
    }

    @Test
    fun `reorder does not copy source coupon or historical menu name`() {
        val fixture = OrderCreationFixture()
        OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
        val sourceCoupon = OrderCreationDatabaseFixture.insertFixedCoupon(jdbcTemplate, fixture, 100)
        val created =
            createOrder.create(
                "source-with-benefit",
                fixture.command(couponIssuanceId = sourceCoupon),
            )
        check(created.status == 201)
        val sourceOrderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
        jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", sourceOrderId)
        jdbcTemplate.update("UPDATE merchant_menu SET name = 'Current renamed menu' WHERE id = ?", fixture.menuId)

        val response =
            reorderOrder.reorder(
                "reorder-no-copy-01",
                ReorderOrderCommand(
                    customerId = fixture.customerId,
                    sourceOrderId = sourceOrderId,
                    pickupSlotId = fixture.pickupSlotId,
                    couponIssuanceId = null,
                    pointsToUseKrw = 0,
                ),
            )

        assertThat(response.status).isEqualTo(201)
        assertThat(response.body)
            .contains("\"menuName\":\"Current renamed menu\"", "\"couponDiscountKrw\":0")
            .doesNotContain(sourceCoupon.toString())
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT coupon_discount_krw FROM ordering_order ORDER BY created_at, id",
                Long::class.java,
            ),
        ).containsExactlyInAnyOrder(100, 0)
    }

    @Test
    fun `reorder metrics use bounded outcomes and never identifier tags`() {
        val source = sourceOrder()
        val successBefore = counter("beanflow.order.reorder.attempts", "outcome", "success")
        val replayBefore = counter("beanflow.order.reorder.attempts", "outcome", "replay")

        reorderOrder.reorder("reorder-metric-01", source.command())
        reorderOrder.reorder("reorder-metric-01", source.command())

        assertThat(counter("beanflow.order.reorder.attempts", "outcome", "success") - successBefore).isEqualTo(1.0)
        assertThat(counter("beanflow.order.reorder.attempts", "outcome", "replay") - replayBefore).isEqualTo(1.0)
        assertThat(
            meterRegistry
                .find("beanflow.order.reorder.attempts")
                .meters()
                .flatMap { it.id.tags }
                .map { it.key }
                .distinct(),
        ).containsOnly("outcome")
    }

    private fun sourceOrder(terminal: Boolean = true): SourceFixture {
        val fixture = OrderCreationFixture()
        OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
        val created = createOrder.create("source-create-${UUID.randomUUID()}", fixture.command())
        check(created.status == 201)
        val orderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
        if (terminal) jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", orderId)
        return SourceFixture(fixture, orderId)
    }

    private fun count(table: String): Long = OrderCreationDatabaseFixture.count(jdbcTemplate, table)

    private fun counter(
        name: String,
        tag: String,
        value: String,
    ): Double = meterRegistry.find(name).tag(tag, value).counter()?.count() ?: 0.0

    private data class SourceFixture(
        val fixture: OrderCreationFixture,
        val orderId: UUID,
    ) {
        fun command(): ReorderOrderCommand =
            ReorderOrderCommand(
                customerId = fixture.customerId,
                sourceOrderId = orderId,
                pickupSlotId = fixture.pickupSlotId,
                couponIssuanceId = null,
                pointsToUseKrw = 0,
            )
    }
}
