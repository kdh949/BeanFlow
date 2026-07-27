package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
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
}
