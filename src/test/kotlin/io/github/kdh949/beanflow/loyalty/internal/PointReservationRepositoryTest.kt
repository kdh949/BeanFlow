package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.ExpiredPointRestorationMode
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.loyalty.api.RestorePointsAfterTerminationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class PointReservationRepositoryTest
    @Autowired
    constructor(
        private val operations: PointReservationOperations,
        private val accountRepository: PointAccountJpaRepository,
        private val lotRepository: PointLotJpaRepository,
        private val reservationRepository: PointReservationJpaRepository,
        private val allocationRepository: PointReservationAllocationJpaRepository,
        private val pointTransactionRepository: PointTransactionJpaRepository,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanDatabase() {
            transactions.executeWithoutResult {
                pointTransactionRepository.deleteAllInBatch()
                allocationRepository.deleteAllInBatch()
                reservationRepository.deleteAllInBatch()
                lotRepository.deleteAllInBatch()
                accountRepository.deleteAllInBatch()
            }
        }

        @Test
        fun `concurrent point reservations never exceed account and lot availability`() {
            val fixture = insertPoints(available = 100)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            val results =
                (1..2)
                    .map { attempt ->
                        executor.submit<Result<Long>> {
                            barrier.await()
                            runCatching {
                                transactions.execute {
                                    operations
                                        .reserve(
                                            command(
                                                fixture = fixture,
                                                orderId = UUID.randomUUID(),
                                                amount = 80,
                                                sourceReference = "points-attempt-$attempt",
                                            ),
                                        ).allocations
                                        .sumOf { it.finalAllocationKrw }
                                }
                            }
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.count(Result<Long>::isSuccess)).isEqualTo(1)
            val failure = results.single(Result<Long>::isFailure).exceptionOrNull()
            assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.POINT_BALANCE_INSUFFICIENT)
            }
            transactions.executeWithoutResult {
                val account = accountRepository.findById(fixture.accountId).orElseThrow()
                assertThat(account.availablePointsKrw).isEqualTo(20)
                assertThat(account.reservedPointsKrw).isEqualTo(80)
                assertThat(allocationRepository.findAll().sumOf { it.amountKrw }).isEqualTo(80)
            }
        }

        @Test
        fun `lots are allocated by expiry then point lot id and tie out exactly`() {
            val customerId = UUID.randomUUID()
            val accountId = UUID.randomUUID()
            val firstLotId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val secondLotId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val sameExpiry = Instant.parse("2030-01-01T00:00:00Z")
            transactions.executeWithoutResult {
                accountRepository.save(PointAccountEntity(accountId, customerId, availablePointsKrw = 100))
                lotRepository.saveAll(
                    listOf(
                        PointLotEntity(
                            id = secondLotId,
                            pointAccountId = accountId,
                            availableAmountKrw = 40,
                            expiresAt = sameExpiry,
                            issuerType = PointIssuerType.BRAND,
                            issuerReference = "brand:second",
                        ),
                        PointLotEntity(
                            id = firstLotId,
                            pointAccountId = accountId,
                            availableAmountKrw = 60,
                            expiresAt = sameExpiry,
                            issuerType = PointIssuerType.STORE,
                            issuerReference = "store:first",
                        ),
                    ),
                )
            }

            val result =
                transactions.execute {
                    operations.reserve(
                        ReservePointsCommand(
                            orderId = UUID.randomUUID(),
                            customerId = customerId,
                            amountKrw = 70,
                            reservationExpiresAt = Instant.parse("2029-01-01T00:05:00Z"),
                            sourceReference = "deterministic-points",
                        ),
                    )
                }

            assertThat(result.allocations.map { it.pointLotId }).containsExactly(firstLotId, secondLotId)
            assertThat(result.allocations.map { it.finalAllocationKrw }).containsExactly(60, 10)
            assertThat(result.allocations.map { it.issuerType })
                .containsExactly(PointIssuerType.STORE, PointIssuerType.BRAND)
            assertThat(result.allocations.map { it.issuerReference })
                .containsExactly("store:first", "brand:second")
            transactions.executeWithoutResult {
                val account = accountRepository.findById(accountId).orElseThrow()
                val lots = lotRepository.findAllById(listOf(firstLotId, secondLotId)).associateBy { it.id }
                assertThat(account.availablePointsKrw).isEqualTo(30)
                assertThat(account.reservedPointsKrw).isEqualTo(70)
                assertThat(lots.getValue(firstLotId).availableAmountKrw).isZero()
                assertThat(lots.getValue(firstLotId).reservedAmountKrw).isEqualTo(60)
                assertThat(lots.getValue(secondLotId).availableAmountKrw).isEqualTo(30)
                assertThat(lots.getValue(secondLotId).reservedAmountKrw).isEqualTo(10)
            }
        }

        @Test
        fun `used unexpired points are restored once with ledger tie out`() {
            val fixture = insertPoints(available = 100)
            val orderId = UUID.randomUUID()
            transactions.executeWithoutResult {
                operations.reserve(command(fixture, orderId, 80, "points-order-$orderId"))
                operations.confirm(orderId, "points-order-$orderId")
            }
            val restore =
                RestorePointsAfterTerminationCommand(
                    orderId = orderId,
                    terminatedAt = Instant.parse("2029-01-01T00:00:00Z"),
                    sourceReference = "rejection-points-$orderId",
                    trigger = OrderTerminationTrigger.STORE_REJECTION,
                    policyVersionId = 2,
                    mode = ExpiredPointRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                )

            val first = operations.restoreUsedAfterTermination(restore)
            val replay = operations.restoreUsedAfterTermination(restore)

            assertThat(first.result).isEqualTo(ReservationTransitionResult.APPLIED)
            assertThat(replay.result).isEqualTo(ReservationTransitionResult.ALREADY_APPLIED)
            transactions.executeWithoutResult {
                val account = accountRepository.findById(fixture.accountId).orElseThrow()
                val lot = lotRepository.findById(fixture.lotId).orElseThrow()
                assertThat(account.availablePointsKrw).isEqualTo(100)
                assertThat(account.reservedPointsKrw).isZero()
                assertThat(lot.availableAmountKrw).isEqualTo(100)
                val reservation = reservationRepository.findByOrderId(orderId)
                assertThat(reservation?.state).isEqualTo(PointReservationState.RESTORED)
                assertThat(reservation?.restorationTrigger).isEqualTo(OrderTerminationTrigger.STORE_REJECTION.name)
                assertThat(reservation?.restorationPolicyVersionId).isEqualTo(2)
                val transactions = pointTransactionRepository.findAll()
                assertThat(transactions.map { it.type })
                    .containsExactlyInAnyOrder(PointTransactionType.USE, PointTransactionType.RESTORE)
                val restoration = transactions.single { it.type == PointTransactionType.RESTORE }
                assertThat(restoration.restorationTrigger).isEqualTo(OrderTerminationTrigger.STORE_REJECTION.name)
                assertThat(restoration.restorationPolicyVersionId).isEqualTo(2)
                assertThat(restoration.restorationDisposition).isEqualTo("ORIGINAL_LOT")
            }

            val conflict =
                org.assertj.core.api.Assertions.catchThrowable {
                    operations.restoreUsedAfterTermination(
                        restore.copy(trigger = OrderTerminationTrigger.CUSTOMER_CANCELLATION),
                    )
                }
            assertThat(conflict).isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.COMPENSATION_SOURCE_CONFLICT)
            }
        }

        @Test
        fun `expired point compensation preserves the immutable issuer snapshot`() {
            val fixture = insertPoints(available = 100)
            val orderId = UUID.randomUUID()
            transactions.executeWithoutResult {
                operations.reserve(command(fixture, orderId, 80, "points-order-$orderId"))
                operations.confirm(orderId, "points-order-$orderId")
            }

            operations.restoreUsedAfterTermination(
                RestorePointsAfterTerminationCommand(
                    orderId = orderId,
                    terminatedAt = Instant.parse("2031-01-01T00:00:00Z"),
                    sourceReference = "rejection-points-$orderId",
                    trigger = OrderTerminationTrigger.CUSTOMER_CANCELLATION,
                    policyVersionId = 4,
                    mode = ExpiredPointRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                ),
            )

            transactions.executeWithoutResult {
                val compensation =
                    lotRepository.findAll().single { it.originalPointLotId == fixture.lotId }
                assertThat(compensation.issuerType).isEqualTo(PointIssuerType.PLATFORM)
                assertThat(compensation.issuerReference).isEqualTo("platform:test-fixture")
                assertThat(compensation.restorationTrigger)
                    .isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION.name)
                assertThat(compensation.restorationPolicyVersionId).isEqualTo(4)
            }
        }

        @Test
        fun `expired customer cancellation preserve policy records skipped restoration without available points`() {
            val fixture = insertPoints(available = 100)
            val orderId = UUID.randomUUID()
            transactions.executeWithoutResult {
                operations.reserve(command(fixture, orderId, 80, "points-order-$orderId"))
                operations.confirm(orderId, "points-order-$orderId")
            }

            val report =
                operations.restoreUsedAfterTermination(
                    RestorePointsAfterTerminationCommand(
                        orderId = orderId,
                        terminatedAt = Instant.parse("2031-01-01T00:00:00Z"),
                        sourceReference = "order:$orderId:customer-cancellation:7:points",
                        trigger = OrderTerminationTrigger.CUSTOMER_CANCELLATION,
                        policyVersionId = 4,
                        mode = ExpiredPointRestorationMode.PRESERVE_ORIGINAL_EXPIRY,
                        compensationValidityDays = 30,
                    ),
                )

            assertThat(report.result).isEqualTo(ReservationTransitionResult.APPLIED)
            transactions.executeWithoutResult {
                val account = accountRepository.findById(fixture.accountId).orElseThrow()
                val reservation = requireNotNull(reservationRepository.findByOrderId(orderId))
                val restoration =
                    pointTransactionRepository
                        .findAll()
                        .single { it.type == PointTransactionType.RESTORE_SKIPPED_EXPIRED }
                assertThat(account.availablePointsKrw).isEqualTo(20)
                assertThat(lotRepository.findAll()).hasSize(1)
                assertThat(reservation.state).isEqualTo(PointReservationState.RESTORED)
                assertThat(reservation.restorationTrigger).isEqualTo(OrderTerminationTrigger.CUSTOMER_CANCELLATION.name)
                assertThat(reservation.restorationPolicyVersionId).isEqualTo(4)
                assertThat(restoration.amountKrw).isEqualTo(80)
                assertThat(restoration.restorationDisposition).isEqualTo("SKIPPED_EXPIRED")
            }
        }

        @Test
        fun `issuer precheck records verified outcome for final issuer snapshots`() {
            insertPoints(available = 100)
            val meterRegistry = SimpleMeterRegistry()

            PointLotIssuerPrecheck(jdbcTemplate, meterRegistry)
                .run(DefaultApplicationArguments())

            assertThat(
                meterRegistry
                    .find("beanflow.loyalty.issuer_precheck.count")
                    .tag("outcome", "VERIFIED")
                    .counter()
                    ?.count(),
            ).isEqualTo(1.0)
        }

        private fun insertPoints(available: Long): PointFixture {
            val fixture =
                PointFixture(
                    customerId = UUID.randomUUID(),
                    accountId = UUID.randomUUID(),
                    lotId = UUID.randomUUID(),
                )
            transactions.executeWithoutResult {
                accountRepository.save(
                    PointAccountEntity(fixture.accountId, fixture.customerId, availablePointsKrw = available),
                )
                lotRepository.save(
                    PointLotEntity(
                        id = fixture.lotId,
                        pointAccountId = fixture.accountId,
                        availableAmountKrw = available,
                        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                        issuerType = PointIssuerType.PLATFORM,
                        issuerReference = "platform:test-fixture",
                    ),
                )
            }
            return fixture
        }

        private fun command(
            fixture: PointFixture,
            orderId: UUID,
            amount: Long,
            sourceReference: String,
        ) = ReservePointsCommand(
            orderId = orderId,
            customerId = fixture.customerId,
            amountKrw = amount,
            reservationExpiresAt = Instant.parse("2029-01-01T00:05:00Z"),
            sourceReference = sourceReference,
        )

        private data class PointFixture(
            val customerId: UUID,
            val accountId: UUID,
            val lotId: UUID,
        )
    }
