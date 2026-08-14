package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.shared.api.DomainFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.point-recovery.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.settlement.batch.initial-delay-ms=3600000",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SettlementBatchLifecycleIntegrationTest
    @Autowired
    constructor(
        private val service: SettlementBatchLifecycleService,
        private val jdbcTemplate: JdbcTemplate,
        private val dataSource: DataSource,
    ) {
        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_dispute,
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    operations_audit_record,
                    event_publication,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `same batch concurrent restart and multi-store calculation converge independently`() {
            val calculationAt = Instant.parse("2026-08-04T00:00:00Z")
            val firstStore = insertStore()
            val firstBatch = insertBatch(firstStore, LocalDate.of(2026, 8, 3))
            insertItem(firstBatch, firstStore, Instant.parse("2026-08-03T14:59:59.999999Z"), 800)
            val secondStore = insertStore()
            val secondBatch = insertBatch(secondStore, LocalDate.of(2026, 8, 3))
            insertItem(secondBatch, secondStore, Instant.parse("2026-08-03T01:00:00Z"), 900)
            val barrier = CyclicBarrier(3)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val futures =
                    listOf(firstBatch, firstBatch, secondBatch).map { batchId ->
                        executor.submit<SettlementBatchLifecycleResult> {
                            barrier.await(5, TimeUnit.SECONDS)
                            service.calculate(batchId, calculationAt)
                        }
                    }
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(results.count { it.settlementBatchId == firstBatch }).isEqualTo(2)
                assertThat(results.count { it.settlementBatchId == secondBatch }).isOne()
            } finally {
                executor.shutdownNow()
            }

            assertThat(value<String>("SELECT state FROM settlement_batch WHERE id = ?", firstBatch))
                .isEqualTo("CALCULATED")
            assertThat(value<Long>("SELECT item_net_settlement_krw FROM settlement_batch WHERE id = ?", firstBatch))
                .isEqualTo(800)
            assertThat(value<Long>("SELECT item_net_settlement_krw FROM settlement_batch WHERE id = ?", secondBatch))
                .isEqualTo(900)
        }

        @Test
        fun `Seoul midnight assigns adjacent dates to separate deterministic summaries`() {
            val storeId = insertStore()
            val augustThird = insertBatch(storeId, LocalDate.of(2026, 8, 3))
            val augustFourth = insertBatch(storeId, LocalDate.of(2026, 8, 4))
            insertItem(augustThird, storeId, Instant.parse("2026-08-03T14:59:59.999999Z"), 700)
            insertItem(augustFourth, storeId, Instant.parse("2026-08-03T15:00:00Z"), 900)

            val third = service.calculate(augustThird, Instant.parse("2026-08-04T15:00:00Z"))
            service.confirm(augustThird, Instant.parse("2026-08-04T15:00:01Z"), "midnight-third")
            val fourth = service.calculate(augustFourth, Instant.parse("2026-08-05T00:00:00Z"))

            assertThat(third.netSettlementKrw).isEqualTo(700)
            assertThat(fourth.netSettlementKrw).isEqualTo(900)
        }

        @Test
        fun `confirmation audit or publication failure leaves calculated batch recoverable`() {
            val storeId = insertStore()
            val batchId = insertBatch(storeId, LocalDate.of(2026, 8, 3))
            insertItem(batchId, storeId, Instant.parse("2026-08-03T01:00:00Z"), 800)
            service.calculate(batchId, Instant.parse("2026-08-04T00:00:00Z"))
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_batch_confirmation " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1')",
            )
            try {
                assertThatThrownBy {
                    service.confirm(batchId, Instant.parse("2026-08-04T00:01:00Z"), "confirmation-failure")
                }.isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE event_publication DROP CONSTRAINT test_reject_batch_confirmation",
                )
            }

            assertThat(value<String>("SELECT state FROM settlement_batch WHERE id = ?", batchId))
                .isEqualTo("CALCULATED")
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_BATCH_CONFIRMED'",
                ),
            ).isZero()

            val confirmed =
                service.confirm(batchId, Instant.parse("2026-08-04T00:02:00Z"), "confirmation-retry")
            val replay =
                service.confirm(batchId, Instant.parse("2026-08-04T00:03:00Z"), "confirmation-replay")

            assertThat(confirmed.state).isEqualTo(SettlementBatchState.CONFIRMED)
            assertThat(replay.confirmedAt).isEqualTo(confirmed.confirmedAt)
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_BATCH_CONFIRMED'",
                ),
            ).isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1",
                ),
            ).isOne()
        }

        @Test
        fun `adjustment and negative carry are consumed once by subsequent confirmed batches`() {
            val storeId = insertStore()
            val first = insertBatch(storeId, LocalDate.of(2026, 8, 1))
            val firstItem = insertItem(first, storeId, Instant.parse("2026-08-01T01:00:00Z"), 800)
            service.calculate(first, Instant.parse("2026-08-02T00:00:00Z"))
            service.confirm(first, Instant.parse("2026-08-02T00:01:00Z"), "first-confirmed")
            insertAdjustment(
                firstItem,
                first,
                storeId,
                amountKrw = -950,
                effectiveAt = Instant.parse("2026-08-02T01:00:00Z"),
                createdAt = Instant.parse("2026-08-02T01:00:01Z"),
            )

            val second = insertBatch(storeId, LocalDate.of(2026, 8, 2))
            insertItem(second, storeId, Instant.parse("2026-08-02T02:00:00Z"), 100)
            val secondResult = service.calculate(second, Instant.parse("2026-08-03T00:00:00Z"))
            service.confirm(second, Instant.parse("2026-08-03T00:01:00Z"), "second-confirmed")

            val third = insertBatch(storeId, LocalDate.of(2026, 8, 3))
            insertItem(third, storeId, Instant.parse("2026-08-03T02:00:00Z"), 1_000)
            val thirdResult = service.calculate(third, Instant.parse("2026-08-04T00:00:00Z"))

            assertThat(secondResult.adjustmentKrw).isEqualTo(-950)
            assertThat(secondResult.netSettlementKrw).isEqualTo(-850)
            assertThat(secondResult.carryForwardOutKrw).isEqualTo(-850)
            assertThat(thirdResult.adjustmentKrw).isZero()
            assertThat(thirdResult.netSettlementKrw).isEqualTo(150)
            assertThat(thirdResult.carryForwardOutKrw).isZero()
        }

        @Test
        fun `later date cannot calculate while an earlier store batch is unconfirmed`() {
            val storeId = insertStore()
            insertBatch(storeId, LocalDate.of(2026, 8, 2))
            val later = insertBatch(storeId, LocalDate.of(2026, 8, 3))
            insertItem(later, storeId, Instant.parse("2026-08-03T01:00:00Z"), 800)

            assertThatThrownBy { service.calculate(later, Instant.parse("2026-08-04T00:00:00Z")) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(io.github.kdh949.beanflow.shared.api.FailureCode.ORDER_STATE_CONFLICT)
                }
            assertThat(value<String>("SELECT state FROM settlement_batch WHERE id = ?", later)).isEqualTo("OPEN")
        }

        @Test
        fun `fixed PostgreSQL fixture records batch keyset duration and lock wait evidence`() {
            val storeId = insertStore()
            val batchId = insertBatch(storeId, LocalDate.of(2026, 8, 3))
            insertMeasurementItems(batchId, storeId, 1_000)
            jdbcTemplate.execute("ANALYZE settlement_item")

            val queryPlan =
                jdbcTemplate.queryForList(
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT id, completed_at, gross_paid_krw, fee_krw,
                           benefit_cost_krw, net_settlement_krw
                      FROM settlement_item
                     WHERE settlement_batch_id = ?
                     ORDER BY completed_at ASC, id ASC
                     LIMIT 500
                    """.trimIndent(),
                    String::class.java,
                    batchId,
                )
            assertThat(queryPlan.joinToString("\n")).contains("idx_settlement_item_batch_cursor")

            val calculationStarted = System.nanoTime()
            val calculated = service.calculate(batchId, Instant.parse("2026-08-04T00:00:00Z"))
            val calculationMillis = elapsedMillis(calculationStarted)
            val confirmationStarted = System.nanoTime()
            service.confirm(batchId, Instant.parse("2026-08-04T00:01:00Z"), "measurement-confirmation")
            val confirmationMillis = elapsedMillis(confirmationStarted)
            val lockWaitMillis = measureBatchLockWait(batchId)

            assertThat(calculated.itemCount).isEqualTo(1_000)
            assertThat(calculated.netSettlementKrw).isEqualTo(800_000)
            assertThat(lockWaitMillis).isGreaterThanOrEqualTo(150.0)
            println(
                "SETTLEMENT_MEASUREMENT " +
                    "postgres=17.6 fixtureItems=1000 chunkSize=500 " +
                    "calculationMs=${formatMillis(calculationMillis)} " +
                    "confirmationMs=${formatMillis(confirmationMillis)} " +
                    "lockWaitMs=${formatMillis(lockWaitMillis)}",
            )
            queryPlan.forEach { println("SETTLEMENT_EXPLAIN $it") }
        }

        private fun insertMeasurementItems(
            batchId: UUID,
            storeId: UUID,
            itemCount: Int,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_public_reference_registry (public_reference, allocated_at)
                SELECT 'BF-' || substr(encoded, 1, 4) || '-' || substr(encoded, 5, 4), now()
                  FROM (
                      SELECT upper(replace(replace(md5('measurement-ref-' || n), '0', 'G'), '1', 'H')) encoded
                        FROM generate_series(1, ?) AS n
                  ) fixture
                """.trimIndent(),
                itemCount,
            )
            jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state,
                        subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                        currency, reservation_expires_at, paid_at, acceptance_warning_at,
                        acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                        created_at, updated_at, version
                    )
                    SELECT md5('measurement-order-' || n)::uuid,
                           md5('measurement-customer-' || n)::uuid,
                           ?,
                           md5('measurement-slot-' || n)::uuid,
                           'BF-' || substr(encoded, 1, 4) || '-' || substr(encoded, 5, 4),
                           date '2026-08-03',
                           n,
                           'Measurement Store',
                           completed_at - interval '10 minutes',
                           completed_at + interval '10 minutes',
                           'COMPLETED', 1000, 0, 0, 1000, 'KRW', NULL,
                           completed_at - interval '180 seconds',
                           completed_at - interval '120 seconds',
                           completed_at - interval '60 seconds',
                           completed_at - interval '150 seconds',
                           completed_at - interval '90 seconds',
                           completed_at - interval '30 seconds',
                           completed_at,
                           completed_at - interval '300 seconds',
                           completed_at,
                           7
                      FROM (
                          SELECT n,
                                 upper(replace(replace(md5('measurement-ref-' || n), '0', 'G'), '1', 'H')) encoded,
                                 timestamptz '2026-08-03 00:00:00+00' +
                                     n * interval '1 millisecond' AS completed_at
                            FROM generate_series(1, ?) AS n
                      ) fixture
                    """.trimIndent(),
                    storeId,
                    itemCount,
                )
            } finally {
                jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                )
                SELECT md5('measurement-item-' || n)::uuid,
                       ?,
                       md5('measurement-order-' || n)::uuid,
                       ?,
                       'order:' || md5('measurement-order-' || n)::uuid || ':completed:7',
                       timestamptz '2026-08-03 00:00:00+00' + n * interval '1 millisecond',
                       date '2026-08-03',
                       'KRW', 1000, 1000, 100, 50, 50, 100, 800, now()
                  FROM generate_series(1, ?) AS n
                """.trimIndent(),
                batchId,
                storeId,
                itemCount,
            )
        }

        private fun measureBatchLockWait(batchId: UUID): Double {
            val lockHeld = CountDownLatch(1)
            val secondAttemptReady = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holder =
                    executor.submit {
                        dataSource.connection.use { connection ->
                            connection.autoCommit = false
                            connection.prepareStatement("SELECT id FROM settlement_batch WHERE id = ? FOR UPDATE").use {
                                it.setObject(1, batchId)
                                it.executeQuery().use { result -> assertThat(result.next()).isTrue() }
                            }
                            lockHeld.countDown()
                            assertThat(releaseLock.await(5, TimeUnit.SECONDS)).isTrue()
                            connection.commit()
                        }
                    }
                assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue()
                val waiter =
                    executor.submit<Double> {
                        dataSource.connection.use { connection ->
                            connection.autoCommit = false
                            secondAttemptReady.countDown()
                            val started = System.nanoTime()
                            connection.prepareStatement("SELECT id FROM settlement_batch WHERE id = ? FOR UPDATE").use {
                                it.setObject(1, batchId)
                                it.executeQuery().use { result -> assertThat(result.next()).isTrue() }
                            }
                            val elapsed = elapsedMillis(started)
                            connection.commit()
                            elapsed
                        }
                    }
                assertThat(secondAttemptReady.await(5, TimeUnit.SECONDS)).isTrue()
                Thread.sleep(200)
                releaseLock.countDown()
                holder.get(5, TimeUnit.SECONDS)
                return waiter.get(5, TimeUnit.SECONDS)
            } finally {
                releaseLock.countDown()
                executor.shutdownNow()
            }
        }

        private fun elapsedMillis(startedAtNanos: Long): Double = (System.nanoTime() - startedAtNanos) / 1_000_000.0

        private fun formatMillis(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertBatch(
            storeId: UUID,
            settlementDate: LocalDate,
        ): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                        "VALUES (?, ?, ?, 'OPEN', now(), 0)",
                    it,
                    storeId,
                    settlementDate,
                )
            }

        private fun insertItem(
            batchId: UUID,
            storeId: UUID,
            completedAt: Instant,
            netSettlementKrw: Long,
        ): UUID {
            val orderId = insertSyntheticCompletedOrder(storeId, completedAt)
            val itemId = UUID.randomUUID()
            val gross = Math.addExact(netSettlementKrw, 100)
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'KRW', ?, 500, 50, 25, 25, 50, ?, now())
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
                Timestamp.from(completedAt),
                completedAt.atZone(SEOUL).toLocalDate(),
                gross,
                netSettlementKrw,
            )
            return itemId
        }

        private fun insertSyntheticCompletedOrder(
            storeId: UUID,
            completedAt: Instant,
        ): UUID =
            UUID.randomUUID().also { orderId ->
                val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id,
                            public_reference, pickup_business_date, pickup_sequence,
                            store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                            state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                                  'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                                  'COMPLETED', 1000, 0, 0, 1000,
                                  'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        publicReference,
                        OrderCreationDatabaseFixture.pickupSequence(orderId),
                        Timestamp.from(completedAt.minusSeconds(180)),
                        Timestamp.from(completedAt.minusSeconds(120)),
                        Timestamp.from(completedAt.minusSeconds(60)),
                        Timestamp.from(completedAt.minusSeconds(150)),
                        Timestamp.from(completedAt.minusSeconds(90)),
                        Timestamp.from(completedAt.minusSeconds(30)),
                        Timestamp.from(completedAt),
                        Timestamp.from(completedAt.minusSeconds(300)),
                        Timestamp.from(completedAt),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun insertAdjustment(
            itemId: UUID,
            batchId: UUID,
            storeId: UUID,
            amountKrw: Long,
            effectiveAt: Instant,
            createdAt: Instant,
        ) {
            val completedAt = value<Instant>("SELECT completed_at FROM settlement_item WHERE id = ?", itemId)
            jdbcTemplate.update(
                """
                INSERT INTO settlement_adjustment (
                    id, store_id, settlement_item_id, source_settlement_batch_id,
                    adjustment_source, reason_code, effective_at, order_completed_at,
                    settlement_date, currency, amount_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, 'REFUND_SUCCEEDED', ?, ?, ?, 'KRW', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                storeId,
                itemId,
                batchId,
                "refund:${UUID.randomUUID()}",
                Timestamp.from(effectiveAt),
                Timestamp.from(completedAt),
                completedAt.atZone(SEOUL).toLocalDate(),
                amountKrw,
                Timestamp.from(createdAt),
            )
        }

        private fun count(
            sql: String,
            vararg arguments: Any,
        ): Long = value(sql, *arguments)

        private inline fun <reified T : Any> value(
            sql: String,
            vararg arguments: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *arguments))

        private companion object {
            val SEOUL = java.time.ZoneId.of("Asia/Seoul")
        }
    }
