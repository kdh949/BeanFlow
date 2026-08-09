package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
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
internal class CreateOrderServiceTest @Autowired constructor(
	private val createOrderUseCase: CreateOrderUseCase,
	private val jdbcTemplate: JdbcTemplate,
	private val meterRegistry: MeterRegistry,
	private val orderCreationRetention: OrderCreationIdempotencyRetentionService,
) {

	@BeforeEach
	fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

	@Test
	fun `order and all required reservations commit together`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		val response = createOrderUseCase.create("success-key-0001", fixture.command())

		assertThat(response.status).isEqualTo(201)
		assertThat(response.body).contains("\"state\":\"PENDING_PAYMENT\"")
		assertThat(response.body).contains("\"reservationExpiresAt\"")
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isEqualTo(1)
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isEqualTo(1)
		val optionSnapshot =
			jdbcTemplate.queryForMap(
				"SELECT option_selection_snapshot_state, normalized_option_ids_json::text " +
					"FROM ordering_order_line",
			)
		assertThat(optionSnapshot["option_selection_snapshot_state"]).isEqualTo("SNAPSHOTTED")
		assertThat(optionSnapshot["normalized_option_ids_json"]).isEqualTo("[]")
	}

	@Test
	fun `terminal order creation idempotency is retained until the exact ninety day boundary`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		createOrderUseCase.create("retention-key-01", fixture.command())
		val retentionExpiresAt =
			requireNotNull(
				jdbcTemplate.queryForObject(
					"SELECT retention_expires_at FROM ordering_idempotency_record",
					java.time.Instant::class.java,
				),
			)

		// PostgreSQL timestamptz stores microseconds, so the representable instant before the boundary is -1µs.
		assertThat(orderCreationRetention.purgeDue(retentionExpiresAt.minusNanos(1_000), 100).deletedCount).isZero()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isOne()
		assertThat(orderCreationRetention.purgeDue(retentionExpiresAt, 100).deletedCount).isOne()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_idempotency_record")).isZero()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
	}

	@Test
	fun `stock failure rolls back earlier pickup reservation`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)

		val response = createOrderUseCase.create("stock-fail-0001", fixture.command())

		assertThat(response.status).isEqualTo(409)
		assertThat(response.body).contains("\"code\":\"STOCK_NOT_AVAILABLE\"")
		assertNoOrderOrReservation()
	}

	@Test
	fun `confirmed domain failure is stored and replayed exactly`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)
		val command = fixture.command()

		val first = createOrderUseCase.create("failure-replay-01", command)
		val replay = createOrderUseCase.create("failure-replay-01", command)

		assertThat(first.status).isEqualTo(409)
		assertThat(replay.status).isEqualTo(first.status)
		assertThat(replay.body).isEqualTo(first.body)
		assertThat(replay.replay).isTrue()
		assertNoOrderOrReservation()
	}

	@Test
	fun `coupon failure rolls back pickup and stock reservations`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		val response = createOrderUseCase.create(
			"coupon-fail-001",
			fixture.command(couponIssuanceId = UUID.randomUUID()),
		)

		assertThat(response.status).isEqualTo(409)
		assertThat(response.body).contains("\"code\":\"COUPON_NOT_AVAILABLE\"")
		assertNoOrderOrReservation()
	}

	@Test
	fun `point failure rolls back pickup and stock reservations`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		val response = createOrderUseCase.create("points-fail-001", fixture.command(pointsToUseKrw = 1))

		assertThat(response.status).isEqualTo(409)
		assertThat(response.body).contains("\"code\":\"POINT_BALANCE_INSUFFICIENT\"")
		assertNoOrderOrReservation()
	}

	@Test
	fun `same key and payload replays the exact first response`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		val command = fixture.command()

		val first = createOrderUseCase.create("replay-key-0001", command)
		val replay = createOrderUseCase.create("replay-key-0001", command)

		assertThat(replay.status).isEqualTo(201)
		assertThat(replay.body).isEqualTo(first.body)
		assertThat(replay.replay).isTrue()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
	}

	@Test
	fun `creation and idempotency outcomes are recorded without identifier tags`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		val successBefore = counter("beanflow.order.creation.attempts", "outcome", "success")
		val replayBefore = counter("beanflow.order.creation.attempts", "outcome", "replay")
		val idempotencyBefore = counter("beanflow.order.idempotency.events", "outcome", "replay")

		createOrderUseCase.create("metric-replay-01", fixture.command())
		createOrderUseCase.create("metric-replay-01", fixture.command())

		assertThat(counter("beanflow.order.creation.attempts", "outcome", "success") - successBefore)
			.isEqualTo(1.0)
		assertThat(counter("beanflow.order.creation.attempts", "outcome", "replay") - replayBefore)
			.isEqualTo(1.0)
		assertThat(counter("beanflow.order.idempotency.events", "outcome", "replay") - idempotencyBefore)
			.isEqualTo(1.0)
		assertThat(
			meterRegistry.find("beanflow.order.creation.attempts").meters()
				.flatMap { it.id.tags }
				.map { it.key }
				.distinct(),
		).containsOnly("outcome")
	}

	@Test
	fun `reservation conflict identifies the owner without order identifiers`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture, stockAvailable = 0)
		val before = counter("beanflow.order.reservation.conflicts", "resource", "stock")

		createOrderUseCase.create("metric-stock-001", fixture.command())

		assertThat(counter("beanflow.order.reservation.conflicts", "resource", "stock") - before)
			.isEqualTo(1.0)
	}

	@Test
	fun `same key with different payload is rejected without another owner reservation`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)

		val first = createOrderUseCase.create("reused-key-001", fixture.command(quantity = 1))
		val conflict = createOrderUseCase.create("reused-key-001", fixture.command(quantity = 2))

		assertThat(first.status).isEqualTo(201)
		assertThat(conflict.status).isEqualTo(409)
		assertThat(conflict.body).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"")
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isEqualTo(1)
	}

	private fun assertNoOrderOrReservation() {
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isZero()
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isZero()
	}

	private fun counter(name: String, tag: String, value: String): Double =
		meterRegistry.find(name).tag(tag, value).counter()?.count() ?: 0.0
}
