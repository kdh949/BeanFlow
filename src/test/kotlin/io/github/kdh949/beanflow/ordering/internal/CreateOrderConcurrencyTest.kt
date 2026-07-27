package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class CreateOrderConcurrencyTest @Autowired constructor(
	private val createOrderUseCase: CreateOrderUseCase,
	private val jdbcTemplate: JdbcTemplate,
) {

	@BeforeEach
	fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

	@Test
	fun `concurrent identical key executes one order transaction`() {
		val fixture = OrderCreationFixture()
		OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
		val command = fixture.command()
		val barrier = CyclicBarrier(2)
		val executor = Executors.newFixedThreadPool(2)

		val futures: List<Future<StoredHttpResponse>> = (1..2).map {
			executor.submit<StoredHttpResponse> {
				barrier.await()
				createOrderUseCase.create("concurrent-key-1", command)
			}
		}
		val responses: List<StoredHttpResponse> =
			futures.map { future -> future.get(15, TimeUnit.SECONDS) }
		executor.shutdown()

		assertThat(responses.map { it.status }).allMatch { it == 201 || it == 409 }
		assertThat(responses).anyMatch { it.status == 201 }
		responses.filter { it.status == 409 }.forEach {
			assertThat(it.body).contains("\"code\":\"IDEMPOTENCY_REQUEST_IN_PROGRESS\"")
			assertThat(it.retryAfterSeconds).isNotNull().isPositive()
		}
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isEqualTo(1)
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "fulfillment_pickup_reservation")).isEqualTo(1)
		assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "inventory_stock_reservation")).isEqualTo(1)
	}
}
