package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.github.kdh949.beanflow.ordering.api.StoredHttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class CreateOrderConcurrencyTest
    @Autowired
    constructor(
        private val createOrderUseCase: CreateOrderUseCase,
        private val orderQuoteUseCase: OrderQuoteUseCase,
        private val orderCreationTransaction: OrderCreationTransaction,
        private val idempotencyService: OrderIdempotencyService,
        transactionManager: PlatformTransactionManager,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `concurrent identical key executes one order transaction`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val command = orderQuoteUseCase.attachCurrentQuote(fixture.command())
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val futures: List<Future<StoredHttpResponse>> =
                (1..2).map {
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

        @Test
        fun `writer first makes the final order observe a stale quote`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val quote = orderQuoteUseCase.quote(fixture.quoteCommand())
            val writerUpdated = CountDownLatch(1)
            val allowWriterCommit = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val writer =
                    executor.submit {
                        transactions.executeWithoutResult {
                            lockStoreForTradeWrite(fixture.storeId)
                            jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", fixture.menuId)
                            writerUpdated.countDown()
                            check(allowWriterCommit.await(5, TimeUnit.SECONDS))
                        }
                    }
                check(writerUpdated.await(5, TimeUnit.SECONDS))

                val order =
                    executor.submit(
                        Callable {
                            createOrderUseCase.create(
                                "writer-first-stale-001",
                                fixture.command(expectedQuoteFingerprint = quote.quoteFingerprint),
                            )
                        },
                    )
                assertThatThrownBy { order.get(300, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                allowWriterCommit.countDown()
                writer.get(5, TimeUnit.SECONDS)
                val response = order.get(10, TimeUnit.SECONDS)

                assertThat(response.status).isEqualTo(409)
                assertThat(response.body).contains("\"code\":\"ORDER_QUOTE_STALE\"")
                assertThat(response.body).contains("\"payableKrw\":1200")
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            } finally {
                allowWriterCommit.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `order first holds the store trade writer until order commit`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val command = orderQuoteUseCase.attachCurrentQuote(fixture.command())
            val registration =
                idempotencyService.register(
                    actorId = fixture.customerId,
                    operation = OrderCreationOperation.DIRECT,
                    idempotencyKey = "order-first-lock-001",
                    payloadHash = CanonicalOrderPayload.hash(command),
                    intendedOrderId = UUID.randomUUID(),
                ) as IdempotencyRegistration.Acquired
            val orderPrepared = CountDownLatch(1)
            val allowOrderCommit = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val order =
                    executor.submit(
                        Callable {
                            transactions.execute {
                                val response =
                                    orderCreationTransaction.create(
                                        registration.recordId,
                                        registration.intendedOrderId,
                                        command,
                                    )
                                orderPrepared.countDown()
                                check(allowOrderCommit.await(5, TimeUnit.SECONDS))
                                response
                            }
                        },
                    )
                check(orderPrepared.await(10, TimeUnit.SECONDS))

                val writer =
                    executor.submit {
                        transactions.executeWithoutResult {
                            lockStoreForTradeWrite(fixture.storeId)
                            jdbcTemplate.update("UPDATE merchant_menu SET base_price_krw = 1200 WHERE id = ?", fixture.menuId)
                        }
                    }
                assertThatThrownBy { writer.get(300, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                allowOrderCommit.countDown()
                assertThat(order.get(10, TimeUnit.SECONDS)?.status).isEqualTo(201)
                writer.get(5, TimeUnit.SECONDS)

                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
                assertThat(jdbcTemplate.queryForObject("SELECT base_price_krw FROM merchant_menu WHERE id = ?", Long::class.java, fixture.menuId))
                    .isEqualTo(1200)
            } finally {
                allowOrderCommit.countDown()
                executor.shutdownNow()
            }
        }

        private fun lockStoreForTradeWrite(storeId: UUID) {
            jdbcTemplate.queryForObject(
                "SELECT id FROM merchant_store WHERE id = ? FOR UPDATE",
                UUID::class.java,
                storeId,
            ) ?: error("Store trade root is missing")
        }

        private fun OrderCreationFixture.quoteCommand() =
            io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand(
                customerId = customerId,
                storeId = storeId,
                pickupSlotId = pickupSlotId,
                lines = command().lines,
                couponIssuanceId = null,
                pointsToUseKrw = 0,
            )
    }
