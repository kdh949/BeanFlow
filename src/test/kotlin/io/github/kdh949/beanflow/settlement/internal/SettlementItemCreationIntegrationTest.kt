package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.eventing.api.EventEnvelope
import io.github.kdh949.beanflow.eventing.api.OrderCompletedV2
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SettlementItemCreationIntegrationTest
    @Autowired
    constructor(
        private val service: SettlementItemCreationService,
        private val jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)

        @BeforeEach
        fun cleanSettlementData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    settlement_item,
                    settlement_batch,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `completion source creates one item audit and analytics outbox across replay`() {
            val storeId = insertStore()
            val orderId = insertSyntheticCompletedOrder(storeId)
            val event = completionEvent(orderId, storeId, 7)

            val firstId = create(event)
            val replayedId = create(event.copy(envelope = event.envelope.copy(eventId = UUID.randomUUID())))

            assertThat(replayedId).isEqualTo(firstId)
            assertThat(count("SELECT count(*) FROM settlement_batch WHERE store_id = ?", storeId)).isOne()
            assertThat(count("SELECT count(*) FROM settlement_item WHERE item_source = ?", event.completionSource))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record " +
                        "WHERE action = 'SETTLEMENT_ITEM_CREATED' AND target_id = ?",
                    firstId,
                ),
            ).isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication " +
                        "WHERE listener_id = 'beanflow.analytics.settlement-item-created-v1' " +
                        "AND event_type = ? AND completion_date IS NULL",
                    "io.github.kdh949.beanflow.eventing.api.SettlementItemCreatedV1",
                ),
            ).isOne()
            assertThat(
                value<String>("SELECT item_source FROM settlement_item WHERE id = ?", firstId),
            ).isEqualTo(event.completionSource)
        }

        @Test
        fun `same store and date concurrent completions share one open batch`() {
            val storeId = insertStore()
            val first = completionEvent(insertSyntheticCompletedOrder(storeId), storeId, 7)
            val second = completionEvent(insertSyntheticCompletedOrder(storeId), storeId, 8)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(first, second).map { event ->
                        executor.submit<UUID> {
                            barrier.await(5, TimeUnit.SECONDS)
                            create(event)
                        }
                    }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertThat(count("SELECT count(*) FROM settlement_batch WHERE store_id = ?", storeId)).isOne()
            assertThat(count("SELECT count(*) FROM settlement_item WHERE store_id = ?", storeId)).isEqualTo(2)
            assertThat(
                count(
                    "SELECT count(DISTINCT settlement_batch_id) FROM settlement_item WHERE store_id = ?",
                    storeId,
                ),
            ).isOne()
        }

        @Test
        fun `audit or item-created outbox failure rolls back the whole consumer transaction`() {
            val storeId = insertStore()
            val event = completionEvent(insertSyntheticCompletedOrder(storeId), storeId, 7)
            jdbcTemplate.execute(
                """
                ALTER TABLE event_publication
                ADD CONSTRAINT test_reject_settlement_item_created
                CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementItemCreatedV1')
                """.trimIndent(),
            )
            try {
                assertThatThrownBy { create(event) }.isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute(
                    "ALTER TABLE event_publication DROP CONSTRAINT test_reject_settlement_item_created",
                )
            }

            assertThat(count("SELECT count(*) FROM settlement_batch WHERE store_id = ?", storeId)).isZero()
            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", event.orderId)).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_ITEM_CREATED'",
                ),
            ).isZero()
        }

        @Test
        fun `closed batch leaves one durable late-item case and keeps the event incomplete`() {
            val storeId = insertStore()
            val event = completionEvent(insertSyntheticCompletedOrder(storeId), storeId, 7)
            jdbcTemplate.update(
                """
                INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version)
                VALUES (?, ?, ?, 'CALCULATED', ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                storeId,
                event.settlementDate,
                Timestamp.from(event.completedAt),
            )

            repeat(2) {
                assertThatThrownBy { create(event) }
                    .isInstanceOf(DomainFailure::class.java)
                    .hasMessageContaining("manual late-item reprocessing")
            }

            assertThat(count("SELECT count(*) FROM settlement_item WHERE order_id = ?", event.orderId)).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_reprocessing_case " +
                        "WHERE case_type = 'SETTLEMENT_LATE_ITEM' AND owner_reference = ? " +
                        "AND status = 'MANUAL_REVIEW'",
                    "settlement-late-item:${event.completionSource}",
                ),
            ).isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_ITEM_CREATED'",
                ),
            ).isZero()
        }

        private fun create(event: OrderCompletedV2): UUID =
            requireNotNull(
                transactions.execute {
                    service.create(event, Instant.parse("2026-08-03T01:03:00Z"))
                },
            )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertSyntheticCompletedOrder(storeId: UUID): UUID =
            UUID.randomUUID().also { orderId ->
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id, state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, 'COMPLETED', 1000, 100, 100, 800,
                                  'KRW', NULL,
                                  '2026-08-03T00:10:00Z', '2026-08-03T00:12:00Z',
                                  '2026-08-03T00:13:00Z', '2026-08-03T00:11:00Z',
                                  '2026-08-03T00:12:00Z', '2026-08-03T00:13:00Z',
                                  '2026-08-03T01:02:03Z', '2026-08-03T00:00:00Z',
                                  '2026-08-03T01:02:03Z', 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun completionEvent(
            orderId: UUID,
            storeId: UUID,
            aggregateVersion: Long,
        ): OrderCompletedV2 {
            val completedAt = Instant.parse("2026-08-03T01:02:03Z")
            return OrderCompletedV2(
                envelope =
                    EventEnvelope(
                        eventId = UUID.randomUUID(),
                        eventType = "OrderCompletedV2",
                        aggregateId = orderId,
                        aggregateVersion = aggregateVersion,
                        occurredAt = completedAt,
                        payloadVersion = 2,
                        correlationId = "settlement-test:$orderId",
                        causationId = "store-order-command:test-$orderId",
                    ),
                orderId = orderId,
                customerId = UUID.randomUUID(),
                storeId = storeId,
                completedAt = completedAt,
                settlementDate = LocalDate.of(2026, 8, 3),
                currency = "KRW",
                grossPaidKrw = 1_000,
                feeRateBps = 500,
                feeKrw = 50,
                couponCostKrw = 100,
                pointCostKrw = 50,
                benefitCostKrw = 150,
                netSettlementKrw = 800,
                completionSource = "order:$orderId:completed:$aggregateVersion",
            )
        }

        private fun count(
            sql: String,
            vararg args: Any,
        ): Long = value(sql, *args)

        private inline fun <reified T : Any> value(
            sql: String,
            vararg args: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *args))
    }
