package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReleasePickupAfterTerminationCommand
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
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
internal class PickupReservationRepositoryTest
    @Autowired
    constructor(
        private val operations: PickupReservationOperations,
        private val slotRepository: PickupSlotJpaRepository,
        private val reservationRepository: PickupReservationJpaRepository,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            transactions.executeWithoutResult {
                reservationRepository.deleteAllInBatch()
                slotRepository.deleteAllInBatch()
            }
        }

        @Test
        fun `last pickup capacity is granted only once under contention`() {
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            insertSlot(slotId, storeId, capacity = 1)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val results =
                (1..2)
                    .map { attempt ->
                        executor.submit<Result<UUID>> {
                            barrier.await()
                            runCatching {
                                transactions.execute {
                                    operations.reserve(
                                        ReservePickupCommand(
                                            orderId = UUID.randomUUID(),
                                            storeId = storeId,
                                            pickupSlotId = slotId,
                                            expiresAt = Instant.parse("2026-07-28T00:05:00Z"),
                                            sourceReference = "pickup-attempt-$attempt",
                                        ),
                                    )
                                }
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<UUID>::isSuccess)).isEqualTo(1)
            val failure = results.single(Result<UUID>::isFailure).exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.PICKUP_SLOT_FULL)
            }
            transactions.executeWithoutResult {
                val slot = slotRepository.findById(slotId).orElseThrow()
                assertThat(slot.reservedCount).isEqualTo(1)
                assertThat(slot.confirmedCount).isZero()
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `same pickup source is idempotent and increments capacity once`() {
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(slotId, storeId, capacity = 2)
            val command =
                ReservePickupCommand(
                    orderId = orderId,
                    storeId = storeId,
                    pickupSlotId = slotId,
                    expiresAt = Instant.parse("2026-07-28T00:05:00Z"),
                    sourceReference = "pickup-order-$orderId",
                )

            transactions.executeWithoutResult {
                val first = operations.reserve(command)
                val replay = operations.reserve(command)
                assertThat(replay).isEqualTo(first)
            }

            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(slotId).orElseThrow().reservedCount).isEqualTo(1)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `confirmed pickup is released after termination exactly once and metadata conflicts fail`() {
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(slotId, storeId, capacity = 2)
            transactions.executeWithoutResult {
                operations.reserve(
                    ReservePickupCommand(
                        orderId,
                        storeId,
                        slotId,
                        Instant.parse("2030-01-01T00:05:00Z"),
                        "pickup-order-$orderId",
                    ),
                )
                operations.confirm(orderId, "pickup-order-$orderId")
            }

            val first =
                operations.releaseConfirmedAfterTermination(
                    ReleasePickupAfterTerminationCommand(
                        orderId,
                        Instant.parse("2029-01-01T00:00:00Z"),
                        "rejection-pickup-$orderId",
                        OrderTerminationTrigger.STORE_REJECTION,
                    ),
                )
            val replay =
                operations.releaseConfirmedAfterTermination(
                    ReleasePickupAfterTerminationCommand(
                        orderId,
                        Instant.parse("2029-01-01T00:00:01Z"),
                        "rejection-pickup-$orderId",
                        OrderTerminationTrigger.STORE_REJECTION,
                    ),
                )

            assertThat(first.result).isEqualTo(ReservationTransitionResult.APPLIED)
            assertThat(replay.result).isEqualTo(ReservationTransitionResult.ALREADY_APPLIED)
            transactions.executeWithoutResult {
                val slot = slotRepository.findById(slotId).orElseThrow()
                assertThat(slot.reservedCount).isZero()
                assertThat(slot.confirmedCount).isZero()
                assertThat(reservationRepository.findByOrderId(orderId)?.state)
                    .isEqualTo(PickupReservationState.RELEASED_AFTER_TERMINATION)
                assertThat(reservationRepository.findByOrderId(orderId)?.restorationTrigger)
                    .isEqualTo(OrderTerminationTrigger.STORE_REJECTION)
            }

            val conflict =
                runCatching {
                    operations.releaseConfirmedAfterTermination(
                        ReleasePickupAfterTerminationCommand(
                            orderId,
                            Instant.parse("2029-01-01T00:00:02Z"),
                            "rejection-pickup-$orderId",
                            OrderTerminationTrigger.CUSTOMER_CANCELLATION,
                        ),
                    )
                }.exceptionOrNull()
            assertThat(conflict).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.COMPENSATION_SOURCE_CONFLICT)
            }
        }

        private fun insertSlot(
            id: UUID,
            storeId: UUID,
            capacity: Long,
        ) {
            transactions.executeWithoutResult {
                slotRepository.saveAndFlush(
                    PickupSlotEntity(
                        id = id,
                        storeId = storeId,
                        startsAt = Instant.parse("2026-07-28T00:10:00Z"),
                        endsAt = Instant.parse("2026-07-28T00:20:00Z"),
                        capacity = capacity,
                    ),
                )
            }
        }
    }
