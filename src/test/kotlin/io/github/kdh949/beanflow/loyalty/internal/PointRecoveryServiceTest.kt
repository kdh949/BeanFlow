package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.AccrualUnitAmount
import io.github.kdh949.beanflow.loyalty.api.AccrualUnitKey
import io.github.kdh949.beanflow.loyalty.api.AccrueCompletedOrderPointsCommand
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.RecoverRefundEarnedPointsCommand
import io.github.kdh949.beanflow.loyalty.api.RecoverRefundEarnedPointsResult
import io.github.kdh949.beanflow.loyalty.api.RefundEarnedPointRecoveryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class PointRecoveryServiceTest
    @Autowired
    constructor(
        private val operations: RefundEarnedPointRecoveryOperations,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    loyalty_point_accrual_result,
                    loyalty_point_recovery_result,
                    loyalty_point_recovery_pending,
                    loyalty_point_transaction,
                    loyalty_partial_refund_restoration,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `refund recovery debits actual lots exactly once and conflicts on changed replay`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, 100)
            val firstLot = insertLot(accountId, 40, NOW.plusSeconds(100), "store:first")
            val secondLot = insertLot(accountId, 60, NOW.plusSeconds(200), "store:second")
            val command = recoveryCommand(customerId, target = 80)

            val first = operations.recover(command)
            val replay = operations.recover(command)

            assertThat(first.recoveredAmountKrw).isEqualTo(80)
            assertThat(first.pendingAmountKrw).isZero()
            assertThat(first.replayed).isFalse()
            assertThat(replay).isEqualTo(first.copy(replayed = true))
            assertThat(long("select available_points_krw from loyalty_point_account")).isEqualTo(20)
            assertThat(lotAmount(firstLot)).isZero()
            assertThat(lotAmount(secondLot)).isEqualTo(20)
            assertThat(long("select count(*) from loyalty_point_recovery_pending")).isZero()
            assertThat(long("select count(*) from loyalty_point_transaction where type = 'RECOVERY'")).isEqualTo(2)
            assertThat(long("select sum(amount_krw) from loyalty_point_transaction where type = 'RECOVERY'")).isEqualTo(80)
            assertThatThrownBy { operations.recover(command.copy(targetAmountKrw = 81)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                }
        }

        @Test
        fun `PointsAccrued publishes gross accrual even when recovery consumes the available balance`() {
            val customerId = UUID.randomUUID()
            val firstRecovery = recoveryCommand(customerId, target = 30, refundSucceededAt = NOW.plusSeconds(1))
            val secondRecovery =
                recoveryCommand(
                    customerId,
                    target = 20,
                    refundId = UUID.randomUUID(),
                    orderId = UUID.randomUUID(),
                    refundSucceededAt = NOW.plusSeconds(2),
                )
            assertThat(operations.recover(firstRecovery).pendingAmountKrw).isEqualTo(30)
            assertThat(operations.recover(secondRecovery).pendingAmountKrw).isEqualTo(20)

            val accrual = operations.accrue(accrualCommand(customerId, gross = 40))

            assertThat(accrual.accruedAmountKrw).isEqualTo(40)
            assertThat(accrual.offsetAmountKrw).isEqualTo(40)
            assertThat(accrual.availableAmountKrw).isZero()
            assertThat(long("select recovery_pending_krw from loyalty_point_account")).isEqualTo(10)
            assertThat(
                jdbcTemplate.queryForList(
                    "select state, remaining_amount_krw from loyalty_point_recovery_pending order by created_at, id",
                ),
            ).extracting("state", "remaining_amount_krw")
                .containsExactly(
                    org.assertj.core.groups.Tuple
                        .tuple("SETTLED", 0L),
                    org.assertj.core.groups.Tuple
                        .tuple("PENDING", 10L),
                )
            assertThat(long("select count(*) from loyalty_point_transaction where type = 'ACCRUAL'")).isEqualTo(1)
            assertThat(long("select sum(amount_krw) from loyalty_point_transaction where type = 'ACCRUAL'")).isEqualTo(40)
            assertThat(long("select count(*) from loyalty_point_transaction where point_recovery_pending_id is not null"))
                .isEqualTo(2)
            assertThat(long("select available_amount_krw from loyalty_point_lot where accrual_order_id is not null"))
                .isZero()
            assertThat(long("select count(*) from event_publication where event_type like '%PointsAccruedV1'"))
                .isEqualTo(1)
            val event = accruedEvent()
            assertThat(event).contains("\"amountKrw\":40")
            assertThat(event).contains("\"orderCompletionSource\":")
        }

        @Test
        fun `PointsAccrued replay preserves one publication and changed snapshot conflicts`() {
            val customerId = UUID.randomUUID()
            val orderId = UUID.randomUUID()
            val firstLine = UUID.randomUUID()
            val secondLine = UUID.randomUUID()
            val command =
                accrualCommand(
                    customerId = customerId,
                    orderId = orderId,
                    gross = 10,
                    units =
                        listOf(
                            AccrualUnitAmount(firstLine, 0, 5),
                            AccrualUnitAmount(secondLine, 0, 5),
                        ),
                    excluded = setOf(AccrualUnitKey(firstLine, 0)),
                )

            val first = operations.accrue(command)
            val replay = operations.accrue(command)

            assertThat(first.excludedAmountKrw).isEqualTo(5)
            assertThat(first.availableAmountKrw).isEqualTo(5)
            assertThat(replay).isEqualTo(first.copy(replayed = true))
            assertThat(long("select count(*) from event_publication where event_type like '%PointsAccruedV1'"))
                .isEqualTo(1)
            assertThatThrownBy {
                operations.accrue(command.copy(excludedUnits = setOf(AccrualUnitKey(secondLine, 0))))
            }.isInstanceOfSatisfying(DomainFailure::class.java) {
                assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
            }
            assertThat(long("select count(*) from event_publication where event_type like '%PointsAccruedV1'"))
                .isEqualTo(1)
        }

        @Test
        fun `PointsAccrued outbox failure rolls back account lot transaction and receipt`() {
            val customerId = UUID.randomUUID()
            jdbcTemplate.execute(
                """
                ALTER TABLE event_publication
                ADD CONSTRAINT test_block_points_accrued
                CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.PointsAccruedV1')
                """.trimIndent(),
            )
            try {
                assertThatThrownBy { operations.accrue(accrualCommand(customerId, gross = 10)) }
                    .isInstanceOf(DomainFailure::class.java)

                assertThat(long("select count(*) from loyalty_point_account")).isZero()
                assertThat(long("select count(*) from loyalty_point_lot")).isZero()
                assertThat(long("select count(*) from loyalty_point_transaction")).isZero()
                assertThat(long("select count(*) from loyalty_point_accrual_result")).isZero()
                assertThat(long("select count(*) from event_publication")).isZero()
            } finally {
                jdbcTemplate.execute("alter table event_publication drop constraint test_block_points_accrued")
            }
        }

        @Test
        fun `concurrent refunds serialize account and lots without negative balance`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, 100)
            insertLot(accountId, 100, NOW.plusSeconds(100), "store:concurrent")
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val commands =
                listOf(
                    recoveryCommand(customerId, target = 80, refundId = UUID.randomUUID(), orderId = UUID.randomUUID()),
                    recoveryCommand(customerId, target = 80, refundId = UUID.randomUUID(), orderId = UUID.randomUUID()),
                )

            val results =
                commands
                    .map { command ->
                        executor.submit<RecoverRefundEarnedPointsResult> {
                            barrier.await()
                            operations.recover(command)
                        }
                    }.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            assertThat(results.sumOf { it.recoveredAmountKrw }).isEqualTo(100)
            assertThat(results.sumOf { it.pendingAmountKrw }).isEqualTo(60)
            assertThat(long("select available_points_krw from loyalty_point_account")).isZero()
            assertThat(long("select recovery_pending_krw from loyalty_point_account")).isEqualTo(60)
            assertThat(long("select available_amount_krw from loyalty_point_lot")).isZero()
            assertThat(long("select sum(remaining_amount_krw) from loyalty_point_recovery_pending where state = 'PENDING'"))
                .isEqualTo(60)
        }

        @Test
        fun `inconsistent available summary rolls back recovery writes`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, 100)
            insertLot(accountId, 50, NOW.plusSeconds(100), "store:broken")

            assertThatThrownBy { operations.recover(recoveryCommand(customerId, target = 80)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
                }
            assertThat(long("select available_points_krw from loyalty_point_account")).isEqualTo(100)
            assertThat(long("select available_amount_krw from loyalty_point_lot")).isEqualTo(50)
            assertThat(long("select count(*) from loyalty_point_recovery_result")).isZero()
            assertThat(long("select count(*) from loyalty_point_recovery_pending")).isZero()
            assertThat(long("select count(*) from loyalty_point_transaction")).isZero()
        }

        private fun insertAccount(
            customerId: UUID,
            available: Long,
        ): UUID =
            UUID.randomUUID().also { accountId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_account (
                        id, customer_id, available_points_krw, reserved_points_krw,
                        recovery_pending_krw, version
                    ) VALUES (?, ?, ?, 0, 0, 0)
                    """.trimIndent(),
                    accountId,
                    customerId,
                    available,
                )
            }

        private fun insertLot(
            accountId: UUID,
            available: Long,
            expiresAt: Instant,
            issuerReference: String,
        ): UUID =
            UUID.randomUUID().also { lotId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_lot (
                        id, point_account_id, available_amount_krw, reserved_amount_krw,
                        expires_at, issuer_type, issuer_reference, version
                    ) VALUES (?, ?, ?, 0, ?, 'STORE', ?, 0)
                    """.trimIndent(),
                    lotId,
                    accountId,
                    available,
                    Timestamp.from(expiresAt),
                    issuerReference,
                )
            }

        private fun recoveryCommand(
            customerId: UUID,
            target: Long,
            refundId: UUID = UUID.randomUUID(),
            orderId: UUID = UUID.randomUUID(),
            refundSucceededAt: Instant = NOW.plusSeconds(1),
        ) = RecoverRefundEarnedPointsCommand(
            refundId = refundId,
            orderId = orderId,
            customerId = customerId,
            refundSucceededAt = refundSucceededAt,
            refundSourceReference = "refund:$refundId:earned-point-recovery",
            completedAt = NOW,
            completionSourceReference = "order:$orderId:completed:1",
            completionAggregateVersion = 1,
            snapshotSchemaVersion = 1,
            snapshotHash = "a".repeat(64),
            targetAmountKrw = target,
            processedAt = refundSucceededAt.plusSeconds(1),
        )

        private fun accrualCommand(
            customerId: UUID,
            orderId: UUID = UUID.randomUUID(),
            gross: Long,
            units: List<AccrualUnitAmount> = listOf(AccrualUnitAmount(UUID.randomUUID(), 0, gross)),
            excluded: Set<AccrualUnitKey> = emptySet(),
        ) = AccrueCompletedOrderPointsCommand(
            orderId = orderId,
            customerId = customerId,
            completedAt = NOW,
            completionSourceReference = "order:$orderId:completed:1",
            completionAggregateVersion = 1,
            snapshotSchemaVersion = 1,
            snapshotHash = "b".repeat(64),
            snapshotGrossAmountKrw = gross,
            issuerType = PointIssuerType.STORE,
            issuerReference = "store:fixture",
            expiresAt = NOW.plusSeconds(30L * 86_400),
            units = units,
            excludedUnits = excluded,
            correlationId = "correlation:$orderId",
            processedAt = NOW.plusSeconds(2),
        )

        private fun lotAmount(lotId: UUID): Long =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "select available_amount_krw from loyalty_point_lot where id = ?",
                    Long::class.java,
                    lotId,
                ),
            )

        private fun long(sql: String): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

        private fun accruedEvent(): String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "select serialized_event from event_publication where event_type like '%PointsAccruedV1'",
                    String::class.java,
                ),
            )

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
        }
    }
