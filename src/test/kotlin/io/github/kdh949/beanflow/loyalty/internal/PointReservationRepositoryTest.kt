package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.PointReservationOperations
import io.github.kdh949.beanflow.loyalty.api.ReservePointsCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
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
internal class PointReservationRepositoryTest @Autowired constructor(
	private val operations: PointReservationOperations,
	private val accountRepository: PointAccountJpaRepository,
	private val lotRepository: PointLotJpaRepository,
	private val reservationRepository: PointReservationJpaRepository,
	private val allocationRepository: PointReservationAllocationJpaRepository,
	private val pointTransactionRepository: PointTransactionJpaRepository,
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

		val results = (1..2).map { attempt ->
			executor.submit<Result<Long>> {
				barrier.await()
				runCatching {
					transactions.execute {
						operations.reserve(
							command(
								fixture = fixture,
								orderId = UUID.randomUUID(),
								amount = 80,
								sourceReference = "points-attempt-$attempt",
							),
						).allocations.sumOf { it.amountKrw }
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
					PointLotEntity(secondLotId, accountId, availableAmountKrw = 40, expiresAt = sameExpiry),
					PointLotEntity(firstLotId, accountId, availableAmountKrw = 60, expiresAt = sameExpiry),
				),
			)
		}

		val result = transactions.execute {
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
		assertThat(result.allocations.map { it.amountKrw }).containsExactly(60, 10)
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

	private fun insertPoints(available: Long): PointFixture {
		val fixture = PointFixture(
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
