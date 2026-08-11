package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationGrant
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
        private val clock: Clock,
        private val jdbcTemplate: JdbcTemplate,
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
                        executor.submit<Result<PickupReservationGrant>> {
                            barrier.await()
                            runCatching {
                                transactions.execute {
                                    operations.reserve(
                                        ReservePickupCommand(
                                            orderId = UUID.randomUUID(),
                                            storeId = storeId,
                                            pickupSlotId = slotId,
                                            expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                                            sourceReference = "pickup-attempt-$attempt",
                                        ),
                                    )
                                }
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<PickupReservationGrant>::isSuccess)).isEqualTo(1)
            val failure = results.single(Result<PickupReservationGrant>::isFailure).exceptionOrNull()
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
                    expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                    sourceReference = "pickup-order-$orderId",
                )

            transactions.executeWithoutResult {
                val first = operations.reserve(command)
                val replay = operations.reserve(command)
                assertThat(replay).isEqualTo(first)
                assertThat(first.startsAt).isEqualTo(
                    slotRepository.findById(slotId).orElseThrow().startsAt,
                )
                assertThat(first.endsAt).isEqualTo(
                    slotRepository.findById(slotId).orElseThrow().endsAt,
                )
            }

            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(slotId).orElseThrow().reservedCount).isEqualTo(1)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `a slot that already started cannot be reserved and leaves every counter unchanged`() {
            val storeId = UUID.randomUUID()
            val started = UUID.randomUUID()
            val finished = UUID.randomUUID()
            insertSlot(started, storeId, capacity = 2, startsAt = clock.instant().minus(Duration.ofMinutes(1)))
            insertSlot(finished, storeId, capacity = 2, startsAt = clock.instant().minus(Duration.ofHours(2)))

            listOf(started, finished).forEach { slotId ->
                val failure =
                    runCatching {
                        transactions.executeWithoutResult {
                            operations.reserve(
                                ReservePickupCommand(
                                    orderId = UUID.randomUUID(),
                                    storeId = storeId,
                                    pickupSlotId = slotId,
                                    expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                                    sourceReference = "pickup-late-$slotId",
                                ),
                            )
                        }
                    }.exceptionOrNull()
                assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
                }
            }

            transactions.executeWithoutResult {
                listOf(started, finished).forEach { slotId ->
                    val slot = slotRepository.findById(slotId).orElseThrow()
                    assertThat(slot.reservedCount).isZero()
                    assertThat(slot.confirmedCount).isZero()
                }
                assertThat(reservationRepository.count()).isZero()
            }
        }

        @Test
        fun `a reservation accepted before the slot started stays replayable after it starts`() {
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(slotId, storeId, capacity = 2)
            val command =
                ReservePickupCommand(
                    orderId = orderId,
                    storeId = storeId,
                    pickupSlotId = slotId,
                    // Sub-microsecond nanoseconds on purpose. `Clock.systemUTC()` produces them on
                    // Linux but not on macOS, so a deadline taken straight from the clock would make
                    // this assertion depend on the host rather than on the behaviour under test.
                    expiresAt = clock.instant().plus(Duration.ofMinutes(5)).plusNanos(1),
                    sourceReference = "pickup-order-$orderId",
                )
            val first = transactions.execute { operations.reserve(command) }

            // Move the slot into the past instead of sleeping: the retry now happens after the
            // window closed, which is exactly the case a payment retry hits.
            val startedAt = clock.instant().minus(Duration.ofMinutes(1))
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plus(Duration.ofMinutes(10))),
                slotId,
            )

            val replay = transactions.execute { operations.reserve(command) }
            // The replay reads the reservation back from PostgreSQL, so this only holds if the
            // granted deadline was minted at the precision the store can hold.
            assertThat(replay).isEqualTo(first)
            assertThat(first?.expiresAt).isEqualTo(first?.expiresAt?.truncatedTo(ChronoUnit.MICROS))
            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(slotId).orElseThrow().reservedCount).isEqualTo(1)
                assertThat(reservationRepository.count()).isEqualTo(1)
            }
        }

        @Test
        fun `confirmation rechecks slot start even when a stored reservation deadline is later`() {
            val storeId = UUID.randomUUID()
            val slotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(slotId, storeId, capacity = 2)
            val source = "pickup-order-$orderId"
            transactions.executeWithoutResult {
                operations.reserve(
                    ReservePickupCommand(
                        orderId = orderId,
                        storeId = storeId,
                        pickupSlotId = slotId,
                        expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                        sourceReference = source,
                    ),
                )
            }

            // Model a legacy/manual row whose slot start changed after reservation. Its stored
            // reservation deadline is still in the future, so confirm must not rely on it alone.
            val startedAt = clock.instant().minus(Duration.ofMinutes(1))
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plus(Duration.ofMinutes(10))),
                slotId,
            )

            val report = transactions.execute { operations.confirm(orderId, clock.instant(), source) }

            assertThat(report.result).isEqualTo(ReservationTransitionResult.NOT_ELIGIBLE)
            transactions.executeWithoutResult {
                val slot = slotRepository.findById(slotId).orElseThrow()
                assertThat(slot.reservedCount).isEqualTo(1)
                assertThat(slot.confirmedCount).isZero()
                assertThat(reservationRepository.findByOrderId(orderId)?.state)
                    .isEqualTo(PickupReservationState.RESERVED)
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
                operations.confirm(orderId, clock.instant(), "pickup-order-$orderId")
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
            startsAt: Instant = clock.instant().plus(Duration.ofHours(1)),
        ) {
            transactions.executeWithoutResult {
                slotRepository.saveAndFlush(
                    PickupSlotEntity(
                        id = id,
                        storeId = storeId,
                        startsAt = startsAt,
                        endsAt = startsAt.plus(Duration.ofMinutes(10)),
                        capacity = capacity,
                    ),
                )
            }
        }
    }
