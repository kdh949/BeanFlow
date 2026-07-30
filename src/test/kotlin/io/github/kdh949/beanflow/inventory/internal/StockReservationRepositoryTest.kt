package io.github.kdh949.beanflow.inventory.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.inventory.api.ReserveStockCommand
import io.github.kdh949.beanflow.inventory.api.StockRequirement
import io.github.kdh949.beanflow.inventory.api.StockReservationOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class StockReservationRepositoryTest
    @Autowired
    constructor(
        private val operations: StockReservationOperations,
        private val stockRepository: SellableStockJpaRepository,
        private val reservationRepository: StockReservationJpaRepository,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            transactions.executeWithoutResult {
                reservationRepository.deleteAllInBatch()
                stockRepository.deleteAllInBatch()
            }
        }

        @Test
        fun `last sellable stock is granted only once under contention`() {
            val storeId = UUID.randomUUID()
            val stockId = UUID.randomUUID()
            insertStock(stockId, storeId, available = 1)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val results =
                (1..2)
                    .map { attempt ->
                        executor.submit<Result<List<UUID>>> {
                            barrier.await()
                            runCatching {
                                transactions.execute {
                                    operations.reserve(
                                        ReserveStockCommand(
                                            orderId = UUID.randomUUID(),
                                            storeId = storeId,
                                            requirements = listOf(StockRequirement(stockId, 1)),
                                            expiresAt = Instant.parse("2026-07-28T00:05:00Z"),
                                            sourceReference = "stock-attempt-$attempt",
                                        ),
                                    )
                                }
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<List<UUID>>::isSuccess)).isEqualTo(1)
            val failure = results.single(Result<List<UUID>>::isFailure).exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.STOCK_NOT_AVAILABLE)
            }
            transactions.executeWithoutResult {
                val stock = stockRepository.findById(stockId).orElseThrow()
                assertThat(stock.availableQuantity).isZero()
                assertThat(stock.reservedQuantity).isEqualTo(1)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `same stock source is idempotent and aggregates requirements once`() {
            val storeId = UUID.randomUUID()
            val stockId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertStock(stockId, storeId, available = 5)
            val command =
                ReserveStockCommand(
                    orderId = orderId,
                    storeId = storeId,
                    requirements =
                        listOf(
                            StockRequirement(stockId, 1),
                            StockRequirement(stockId, 2),
                        ),
                    expiresAt = Instant.parse("2026-07-28T00:05:00Z"),
                    sourceReference = "stock-order-$orderId",
                )

            transactions.executeWithoutResult {
                val first = operations.reserve(command)
                val replay = operations.reserve(command)
                assertThat(replay).isEqualTo(first)
            }

            transactions.executeWithoutResult {
                val stock = stockRepository.findById(stockId).orElseThrow()
                assertThat(stock.availableQuantity).isEqualTo(2)
                assertThat(stock.reservedQuantity).isEqualTo(3)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `confirmed stock is restored by rejection exactly once`() {
            val storeId = UUID.randomUUID()
            val stockId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertStock(stockId, storeId, available = 5)
            transactions.executeWithoutResult {
                operations.reserve(
                    ReserveStockCommand(
                        orderId,
                        storeId,
                        listOf(StockRequirement(stockId, 3)),
                        Instant.parse("2030-01-01T00:05:00Z"),
                        "stock-order-$orderId",
                    ),
                )
                operations.confirm(orderId, "stock-order-$orderId")
            }

            val first =
                operations.restoreConfirmedByRejection(
                    orderId,
                    Instant.parse("2029-01-01T00:00:00Z"),
                    "rejection-stock-$orderId",
                )
            val replay =
                operations.restoreConfirmedByRejection(
                    orderId,
                    Instant.parse("2029-01-01T00:00:01Z"),
                    "rejection-stock-$orderId",
                )

            assertThat(first.result).isEqualTo(ReservationTransitionResult.APPLIED)
            assertThat(replay.result).isEqualTo(ReservationTransitionResult.ALREADY_APPLIED)
            transactions.executeWithoutResult {
                val stock = stockRepository.findById(stockId).orElseThrow()
                assertThat(stock.availableQuantity).isEqualTo(5)
                assertThat(stock.reservedQuantity).isZero()
                assertThat(stock.confirmedQuantity).isZero()
                assertThat(reservationRepository.findByOrderIdOrderBySellableUnitId(orderId).single().state)
                    .isEqualTo(StockReservationState.RELEASED_BY_REJECTION)
            }
        }

        private fun insertStock(
            id: UUID,
            storeId: UUID,
            available: Long,
        ) {
            transactions.executeWithoutResult {
                stockRepository.saveAndFlush(
                    SellableStockEntity(
                        id = id,
                        storeId = storeId,
                        availableQuantity = available,
                    ),
                )
            }
        }
    }
