package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationGrant
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReleasePickupAfterTerminationCommand
import io.github.kdh949.beanflow.fulfillment.api.ReschedulePickupCommand
import io.github.kdh949.beanflow.fulfillment.api.ReschedulePickupResult
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
        private val rescheduleHistoryRepository: PickupRescheduleHistoryJpaRepository,
        private val clock: Clock,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            transactions.executeWithoutResult {
                jdbcTemplate.execute("TRUNCATE TABLE fulfillment_pickup_reschedule_history")
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
        fun `reserved pickup reschedule secures the new slot first and exact replay changes counters once`() {
            val storeId = UUID.randomUUID()
            val oldSlotId = UUID.randomUUID()
            val newSlotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(oldSlotId, storeId, capacity = 1)
            insertSlot(newSlotId, storeId, capacity = 1)
            transactions.executeWithoutResult {
                operations.reserve(
                    ReservePickupCommand(
                        orderId,
                        storeId,
                        oldSlotId,
                        clock.instant().plus(Duration.ofMinutes(5)),
                        "pickup-order-$orderId",
                    ),
                )
            }
            val command = ReschedulePickupCommand(orderId, storeId, newSlotId, "support-reschedule-$orderId")

            val first = transactions.execute { operations.reschedule(command) }
            val replay = transactions.execute { operations.reschedule(command) }

            assertThat(first?.result).isEqualTo(ReschedulePickupResult.APPLIED)
            assertThat(replay?.result).isEqualTo(ReschedulePickupResult.ALREADY_APPLIED)
            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(oldSlotId).orElseThrow().reservedCount).isZero()
                assertThat(slotRepository.findById(newSlotId).orElseThrow().reservedCount).isOne()
                assertThat(reservationRepository.findByOrderId(orderId)?.slotId).isEqualTo(newSlotId)
                assertThat(rescheduleHistoryRepository.count()).isOne()
            }
        }

        @Test
        fun `confirmed pickup reschedule transfers confirmed capacity without changing lifecycle state`() {
            val storeId = UUID.randomUUID()
            val oldSlotId = UUID.randomUUID()
            val newSlotId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            insertSlot(oldSlotId, storeId, capacity = 1)
            insertSlot(newSlotId, storeId, capacity = 1)
            transactions.executeWithoutResult {
                operations.reserve(
                    ReservePickupCommand(
                        orderId,
                        storeId,
                        oldSlotId,
                        clock.instant().plus(Duration.ofMinutes(5)),
                        "pickup-order-$orderId",
                    ),
                )
                operations.confirm(orderId, clock.instant(), "pickup-order-$orderId")
                operations.reschedule(ReschedulePickupCommand(orderId, storeId, newSlotId, "confirmed-reschedule-$orderId"))
            }

            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(oldSlotId).orElseThrow().confirmedCount).isZero()
                assertThat(slotRepository.findById(newSlotId).orElseThrow().confirmedCount).isOne()
                val reservation = reservationRepository.findByOrderId(orderId)
                assertThat(reservation?.slotId).isEqualTo(newSlotId)
                assertThat(reservation?.state).isEqualTo(PickupReservationState.CONFIRMED)
            }
        }

        @Test
        fun `last reschedule slot is granted once and losing order keeps its previous slot`() {
            val storeId = UUID.randomUUID()
            val nextSlotId = UUID.randomUUID()
            val orderIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            val oldSlotIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            insertSlot(nextSlotId, storeId, capacity = 1)
            oldSlotIds.forEach { insertSlot(it, storeId, capacity = 1) }
            transactions.executeWithoutResult {
                orderIds.zip(oldSlotIds).forEach { (orderId, oldSlotId) ->
                    operations.reserve(
                        ReservePickupCommand(
                            orderId,
                            storeId,
                            oldSlotId,
                            clock.instant().plus(Duration.ofMinutes(5)),
                            "pickup-order-$orderId",
                        ),
                    )
                }
            }
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val results =
                orderIds
                    .map { orderId ->
                        executor.submit<Result<ReschedulePickupResult>> {
                            barrier.await()
                            runCatching {
                                requireNotNull(
                                    transactions.execute {
                                        operations.reschedule(
                                            ReschedulePickupCommand(orderId, storeId, nextSlotId, "contended-$orderId"),
                                        )
                                    },
                                ).result
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<ReschedulePickupResult>::isSuccess)).isOne()
            assertThat(results.single(Result<ReschedulePickupResult>::isFailure).exceptionOrNull())
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.PICKUP_SLOT_FULL)
                }
            transactions.executeWithoutResult {
                assertThat(slotRepository.findById(nextSlotId).orElseThrow().reservedCount).isOne()
                assertThat(oldSlotIds.sumOf { slotRepository.findById(it).orElseThrow().reservedCount }).isOne()
                assertThat(orderIds.count { reservationRepository.findByOrderId(it)?.slotId == nextSlotId }).isOne()
            }
        }

        @Test
        fun `reschedule races with confirm and release without stale slot counters`() {
            listOf("confirm", "release").forEach { transition ->
                val fixture = reservedFixture()
                val results =
                    runConcurrently(
                        {
                            transactions.execute {
                                operations.reschedule(
                                    ReschedulePickupCommand(
                                        fixture.orderId,
                                        fixture.storeId,
                                        fixture.newSlotId,
                                        "race-reschedule-$transition-${fixture.orderId}",
                                    ),
                                )
                            }
                        },
                        {
                            transactions.execute {
                                when (transition) {
                                    "confirm" -> operations.confirm(fixture.orderId, clock.instant(), fixture.sourceReference)
                                    else -> operations.release(fixture.orderId, clock.instant(), fixture.sourceReference)
                                }
                            }
                        },
                    )

                assertNoLockFailure(results)
                assertReservationCountersTieOut(fixture)
            }
        }

        @Test
        fun `reschedule races with expiry and termination release without stale slot counters`() {
            val expiring = reservedFixture()
            val expiryResults =
                runConcurrently(
                    {
                        transactions.execute {
                            operations.reschedule(
                                ReschedulePickupCommand(
                                    expiring.orderId,
                                    expiring.storeId,
                                    expiring.newSlotId,
                                    "race-reschedule-expire-${expiring.orderId}",
                                ),
                            )
                        }
                    },
                    {
                        transactions.execute {
                            operations.expire(
                                expiring.orderId,
                                clock.instant().plus(Duration.ofMinutes(10)),
                                expiring.sourceReference,
                            )
                        }
                    },
                )
            assertNoLockFailure(expiryResults)
            assertReservationCountersTieOut(expiring)

            val terminating = reservedFixture()
            transactions.executeWithoutResult {
                operations.confirm(terminating.orderId, clock.instant(), terminating.sourceReference)
            }
            val terminationResults =
                runConcurrently(
                    {
                        transactions.execute {
                            operations.reschedule(
                                ReschedulePickupCommand(
                                    terminating.orderId,
                                    terminating.storeId,
                                    terminating.newSlotId,
                                    "race-reschedule-termination-${terminating.orderId}",
                                ),
                            )
                        }
                    },
                    {
                        operations.releaseConfirmedAfterTermination(
                            ReleasePickupAfterTerminationCommand(
                                terminating.orderId,
                                clock.instant(),
                                "race-termination-${terminating.orderId}",
                                OrderTerminationTrigger.STORE_REJECTION,
                            ),
                        )
                    },
                )
            assertNoLockFailure(terminationResults)
            assertReservationCountersTieOut(terminating)
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

        private fun reservedFixture(): ReservationFixture {
            val fixture =
                ReservationFixture(
                    storeId = UUID.randomUUID(),
                    oldSlotId = UUID.randomUUID(),
                    newSlotId = UUID.randomUUID(),
                    orderId = UUID.randomUUID(),
                )
            insertSlot(fixture.oldSlotId, fixture.storeId, capacity = 2)
            insertSlot(fixture.newSlotId, fixture.storeId, capacity = 2)
            transactions.executeWithoutResult {
                operations.reserve(
                    ReservePickupCommand(
                        UUID.randomUUID(),
                        fixture.storeId,
                        fixture.oldSlotId,
                        clock.instant().plus(Duration.ofMinutes(5)),
                        "baseline-pickup-${fixture.orderId}",
                    ),
                )
                operations.reserve(
                    ReservePickupCommand(
                        fixture.orderId,
                        fixture.storeId,
                        fixture.oldSlotId,
                        clock.instant().plus(Duration.ofMinutes(5)),
                        fixture.sourceReference,
                    ),
                )
            }
            return fixture
        }

        private fun runConcurrently(
            first: () -> Any?,
            second: () -> Any?,
        ): List<Result<Any?>> {
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            return try {
                listOf(first, second)
                    .map { operation ->
                        executor.submit<Result<Any?>> {
                            barrier.await()
                            runCatching(operation)
                        }
                    }.map { future -> future.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }

        private fun assertNoLockFailure(results: List<Result<Any?>>) {
            results.mapNotNull(Result<Any?>::exceptionOrNull).forEach { failure ->
                assertThat(failure).isInstanceOf(DomainFailure::class.java)
                assertThat((failure as DomainFailure).code)
                    .isIn(FailureCode.ORDER_STATE_CONFLICT, FailureCode.RESERVATION_EXPIRED)
            }
        }

        private fun assertReservationCountersTieOut(fixture: ReservationFixture) {
            transactions.executeWithoutResult {
                val reservation = requireNotNull(reservationRepository.findByOrderId(fixture.orderId))
                val oldSlot = slotRepository.findById(fixture.oldSlotId).orElseThrow()
                val newSlot = slotRepository.findById(fixture.newSlotId).orElseThrow()
                val occupied =
                    when (reservation.state) {
                        PickupReservationState.RESERVED,
                        PickupReservationState.CONFIRMED,
                        -> 1L

                        else -> 0L
                    }
                assertThat(oldSlot.reservedCount + oldSlot.confirmedCount + newSlot.reservedCount + newSlot.confirmedCount)
                    .isEqualTo(occupied + 1L)
                if (reservation.slotId == fixture.oldSlotId) {
                    assertThat(oldSlot.reservedCount + oldSlot.confirmedCount).isEqualTo(occupied + 1L)
                    assertThat(newSlot.reservedCount + newSlot.confirmedCount).isZero()
                } else {
                    assertThat(oldSlot.reservedCount + oldSlot.confirmedCount).isOne()
                    assertThat(newSlot.reservedCount + newSlot.confirmedCount).isEqualTo(occupied)
                }
            }
        }

        private data class ReservationFixture(
            val storeId: UUID,
            val oldSlotId: UUID,
            val newSlotId: UUID,
            val orderId: UUID,
        ) {
            val sourceReference: String = "pickup-order-$orderId"
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
