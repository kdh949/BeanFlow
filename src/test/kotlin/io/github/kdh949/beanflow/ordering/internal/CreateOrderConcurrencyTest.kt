package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.MenuCatalogOperations
import io.github.kdh949.beanflow.merchant.api.MenuConfigurationTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuSellableRequirement
import io.github.kdh949.beanflow.merchant.api.MenuTradeDefinition
import io.github.kdh949.beanflow.merchant.api.MerchantOrderQuoteOperations
import io.github.kdh949.beanflow.merchant.api.QuoteOrderLine
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuTradeContentCommand
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreOrderingPolicyCommand
import io.github.kdh949.beanflow.merchant.api.StoreOrderingPolicyOperations
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
import java.time.Instant
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
        private val merchantQuotes: MerchantOrderQuoteOperations,
        private val menuCatalog: MenuCatalogOperations,
        private val storeOrderingPolicies: StoreOrderingPolicyOperations,
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
            transactions.executeWithoutResult {
                replaceMenuTrade(
                    fixture,
                    priceKrw = 1200,
                    expectedVersion = 0,
                    key = "menu-writer-first-001",
                    addVariant = true,
                    quantityPerLineUnit = 2,
                )
            }
            val writerUpdated = CountDownLatch(1)
            val allowWriterCommit = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val writer =
                    executor.submit {
                        transactions.executeWithoutResult {
                            replaceMenuTrade(
                                fixture,
                                priceKrw = 1000,
                                expectedVersion = 1,
                                key = "menu-writer-first-002",
                            )
                            writerUpdated.countDown()
                            check(allowWriterCommit.await(5, TimeUnit.SECONDS))
                        }
                    }
                if (!writerUpdated.await(5, TimeUnit.SECONDS)) {
                    writer.get(1, TimeUnit.SECONDS)
                    error("Menu writer did not reach the uncommitted state")
                }

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
                assertThat(response.body).contains("\"payableKrw\":1000")
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
                assertThat(
                    jdbcTemplate.queryForObject("SELECT trade_version FROM merchant_menu WHERE id = ?", Long::class.java, fixture.menuId),
                ).isEqualTo(2)
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
                            replaceMenuTrade(fixture, 1200, expectedVersion = 0, key = "menu-order-first-001")
                        }
                    }
                assertThatThrownBy { writer.get(300, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                allowOrderCommit.countDown()
                assertThat(order.get(10, TimeUnit.SECONDS)?.status).isEqualTo(201)
                writer.get(5, TimeUnit.SECONDS)

                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
                assertThat(
                    jdbcTemplate.queryForObject("SELECT base_price_krw FROM merchant_menu WHERE id = ?", Long::class.java, fixture.menuId),
                ).isEqualTo(1200)
            } finally {
                allowOrderCommit.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `ordering policy writer first makes the final order observe a stale quote`() {
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
                            replaceOrderingPolicy(
                                fixture,
                                expectedVersion = 0,
                                acceptingOrders = false,
                                key = "policy-off-001",
                            )
                            replaceOrderingPolicy(
                                fixture,
                                expectedVersion = 1,
                                acceptingOrders = true,
                                key = "policy-on-0001",
                            )
                            writerUpdated.countDown()
                            check(allowWriterCommit.await(5, TimeUnit.SECONDS))
                        }
                    }
                check(writerUpdated.await(5, TimeUnit.SECONDS))

                val order =
                    executor.submit(
                        Callable {
                            createOrderUseCase.create(
                                "policy-writer-first-001",
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
                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT ordering_policy_version FROM merchant_store WHERE id = ?",
                        Long::class.java,
                        fixture.storeId,
                    ),
                ).isEqualTo(2)
            } finally {
                allowWriterCommit.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `order first holds the ordering policy writer until order commit`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val command = orderQuoteUseCase.attachCurrentQuote(fixture.command())
            val registration =
                idempotencyService.register(
                    actorId = fixture.customerId,
                    operation = OrderCreationOperation.DIRECT,
                    idempotencyKey = "order-first-policy-lock-001",
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
                            replaceOrderingPolicy(
                                fixture,
                                expectedVersion = 0,
                                acceptingOrders = false,
                                key = "policy-after-order-001",
                            )
                        }
                    }
                assertThatThrownBy { writer.get(300, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)

                allowOrderCommit.countDown()
                assertThat(order.get(10, TimeUnit.SECONDS)?.status).isEqualTo(201)
                writer.get(5, TimeUnit.SECONDS)

                assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT accepting_orders FROM merchant_store WHERE id = ?",
                        Boolean::class.java,
                        fixture.storeId,
                    ),
                ).isFalse()
            } finally {
                allowOrderCommit.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `same Store order snapshots hold compatible shared locks`() {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val bothSnapshotsLoaded = CountDownLatch(2)
            val allowCommit = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val readers =
                    (1..2).map {
                        executor.submit {
                            transactions.executeWithoutResult {
                                merchantQuotes.lockForOrderCreation(
                                    fixture.storeId,
                                    listOf(QuoteOrderLine(fixture.menuId, emptyList(), quantity = 1)),
                                )
                                bothSnapshotsLoaded.countDown()
                                check(allowCommit.await(5, TimeUnit.SECONDS))
                            }
                        }
                    }

                assertThat(bothSnapshotsLoaded.await(5, TimeUnit.SECONDS)).isTrue()
                allowCommit.countDown()
                readers.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                allowCommit.countDown()
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

        private fun replaceMenuTrade(
            fixture: OrderCreationFixture,
            priceKrw: Long,
            expectedVersion: Long,
            key: String,
            addVariant: Boolean = false,
            quantityPerLineUnit: Long = 1,
        ) {
            val configurationId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM merchant_menu_configuration " +
                            "WHERE menu_id = ? AND lifecycle = 'ACTIVE' AND normalized_option_key = ''",
                        UUID::class.java,
                        fixture.menuId,
                    ),
                )
            menuCatalog.replace(
                ReplaceMenuTradeContentCommand(
                    actorId = fixture.customerId,
                    idempotencyKey = key,
                    storeId = fixture.storeId,
                    menuId = fixture.menuId,
                    expectedVersion = expectedVersion,
                    definition =
                        MenuTradeDefinition(
                            menuId = fixture.menuId,
                            name = "Americano",
                            basePriceKrw = priceKrw,
                            available = true,
                            options =
                                if (addVariant) {
                                    listOf(
                                        io.github.kdh949.beanflow.merchant.api.MenuOptionTradeContent(
                                            VARIANT_OPTION_ID,
                                            "Extra shot",
                                            500,
                                            available = true,
                                        ),
                                    )
                                } else {
                                    emptyList()
                                },
                            configurations =
                                listOf(
                                    MenuConfigurationTradeContent(
                                        configurationId,
                                        emptyList(),
                                        available = true,
                                        requirements = listOf(MenuSellableRequirement(fixture.sellableUnitId, quantityPerLineUnit)),
                                    ),
                                ) +
                                    if (addVariant) {
                                        listOf(
                                            MenuConfigurationTradeContent(
                                                VARIANT_CONFIGURATION_ID,
                                                listOf(VARIANT_OPTION_ID),
                                                available = true,
                                                requirements = listOf(MenuSellableRequirement(fixture.sellableUnitId, 1)),
                                            ),
                                        )
                                    } else {
                                        emptyList()
                                    },
                        ),
                    now = Instant.parse("2026-08-27T00:00:00Z"),
                ),
            )
        }

        private fun replaceOrderingPolicy(
            fixture: OrderCreationFixture,
            expectedVersion: Long,
            acceptingOrders: Boolean,
            key: String,
        ) {
            storeOrderingPolicies.replace(
                ReplaceStoreOrderingPolicyCommand(
                    actorId = fixture.customerId,
                    idempotencyKey = key,
                    storeId = fixture.storeId,
                    acceptingOrders = acceptingOrders,
                    pickupEnabled = true,
                    expectedVersion = expectedVersion,
                    now = Instant.parse("2026-08-27T00:00:00Z").plusSeconds(expectedVersion),
                ),
            )
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

        private companion object {
            val VARIANT_OPTION_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000101")
            val VARIANT_CONFIGURATION_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000102")
        }
    }
