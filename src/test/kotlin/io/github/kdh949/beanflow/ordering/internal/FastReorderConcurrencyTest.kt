package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class FastReorderConcurrencyTest
    @Autowired
    constructor(
        private val createOrder: CreateOrderUseCase,
        private val reorderOrder: ReorderOrderUseCase,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `concurrent identical key executes at most one reorder transaction`() {
            val command = sourceCommand()

            val responses = concurrently(listOf("same-reorder-key", "same-reorder-key"), command)

            assertThat(responses.map(StoredHttpResponse::status)).allMatch { it == 201 || it == 409 }
            assertThat(responses).anyMatch { it.status == 201 }
            responses.filter { it.status == 409 }.forEach {
                assertThat(it.body).contains("\"code\":\"IDEMPOTENCY_REQUEST_IN_PROGRESS\"")
                assertThat(it.retryAfterSeconds).isNotNull().isPositive()
            }
            assertThat(count("ordering_order")).isEqualTo(2)
            assertThat(count("fulfillment_pickup_reservation")).isEqualTo(2)
            assertThat(count("inventory_stock_reservation")).isEqualTo(2)
        }

        @Test
        fun `concurrent different keys for the same source remain separate commands`() {
            val command = sourceCommand()

            val responses = concurrently(listOf("reorder-key-one", "reorder-key-two"), command)

            assertThat(responses.map(StoredHttpResponse::status)).containsOnly(201)
            assertThat(count("ordering_order")).isEqualTo(3)
            assertThat(count("fulfillment_pickup_reservation")).isEqualTo(3)
            assertThat(count("inventory_stock_reservation")).isEqualTo(3)
        }

        private fun sourceCommand(): ReorderOrderCommand {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            check(createOrder.create("concurrent-source", fixture.command()).status == 201)
            val sourceOrderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
            jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", sourceOrderId)
            return ReorderOrderCommand(
                customerId = fixture.customerId,
                sourceOrderId = sourceOrderId,
                pickupSlotId = fixture.pickupSlotId,
                couponIssuanceId = null,
                pointsToUseKrw = 0,
            )
        }

        private fun concurrently(
            keys: List<String>,
            command: ReorderOrderCommand,
        ): List<StoredHttpResponse> {
            val barrier = CyclicBarrier(keys.size)
            val executor = Executors.newFixedThreadPool(keys.size)
            return try {
                val futures: List<Future<StoredHttpResponse>> =
                    keys.map { key ->
                        executor.submit<StoredHttpResponse> {
                            barrier.await()
                            reorderOrder.reorder(key, command)
                        }
                    }
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdown()
            }
        }

        private fun count(table: String): Long = OrderCreationDatabaseFixture.count(jdbcTemplate, table)
    }
