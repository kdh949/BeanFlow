package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest
internal class FastReorderIdempotencyReconciliationTest
    @Autowired
    constructor(
        private val worker: OrderIdempotencyReconciliationWorker,
        private val idempotency: OrderIdempotencyService,
        private val createOrder: CreateOrderUseCase,
        private val reorderOrder: ReorderOrderUseCase,
        private val jdbcTemplate: JdbcTemplate,
        private val clock: Clock,
    ) {
        @BeforeEach
        fun cleanDatabase() = OrderCreationDatabaseFixture.clean(jdbcTemplate)

        @Test
        fun `stuck reorder processing is isolated for manual review and never auto executes`() {
            val actorId = UUID.randomUUID()
            val key = "stuck-reorder-key"
            val payloadHash = "a".repeat(64)
            insertProcessing(actorId, key, payloadHash, clock.instant().minus(Duration.ofMinutes(10)))

            assertThat(worker.runOnce()).isOne()

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT status FROM ordering_idempotency_record WHERE actor_id = ? AND operation = 'REORDER_ORDER_V1'",
                    String::class.java,
                    actorId,
                ),
            ).isEqualTo("MANUAL_REVIEW")
            assertThat(manualReviewMetadata(actorId)).containsEntry("manual_review_reason", "ORDER_NOT_FOUND")
            assertThat(manualReviewMetadata(actorId)).containsEntry("intended_order_exists", false)
            assertThat(manualReviewMetadata(actorId)["manual_review_started_at"]).isNotNull()
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
            assertThat(
                idempotency.register(
                    actorId = actorId,
                    operation = OrderCreationOperation.REORDER,
                    idempotencyKey = key,
                    payloadHash = payloadHash,
                    intendedOrderId = UUID.randomUUID(),
                ),
            ).isEqualTo(IdempotencyRegistration.ManualReviewRequired)
        }

        @Test
        fun `stuck processing records whether its intended order exists`() {
            val source = sourceOrder()
            val actorId = source.fixture.customerId
            insertProcessing(
                actorId = actorId,
                key = "stuck-order-found",
                payloadHash = "c".repeat(64),
                startedAt = clock.instant().minus(Duration.ofMinutes(10)),
                intendedOrderId = source.orderId,
            )

            assertThat(worker.runOnce()).isOne()

            assertThat(manualReviewMetadata(actorId)).containsEntry("manual_review_reason", "ORDER_FOUND")
            assertThat(manualReviewMetadata(actorId)).containsEntry("intended_order_exists", true)
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isOne()
        }

        @Test
        fun `failed Tx I2 leaves processing then reconciliation stops automatic execution`() {
            val source = sourceOrder()
            val key = "reorder-tx-i2-failure"
            val orderBefore = count("ordering_order")
            val pickupBefore = count("fulfillment_pickup_reservation")
            val stockBefore = count("inventory_stock_reservation")
            val auditBefore = count("operations_audit_record")
            jdbcTemplate.update(
                "UPDATE inventory_sellable_stock SET available_quantity = 0 WHERE id = ?",
                source.fixture.sellableUnitId,
            )
            installFailedTransitionTrigger()
            val first =
                try {
                    reorderOrder.reorder(key, source.command())
                } finally {
                    removeFailedTransitionTrigger()
                }

            assertThat(first.status).isEqualTo(503)
            assertThat(first.body).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"")
            assertThat(count("ordering_order")).isEqualTo(orderBefore)
            assertThat(count("fulfillment_pickup_reservation")).isEqualTo(pickupBefore)
            assertThat(count("inventory_stock_reservation")).isEqualTo(stockBefore)
            assertThat(count("operations_audit_record")).isEqualTo(auditBefore)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT status FROM ordering_idempotency_record " +
                        "WHERE operation = 'REORDER_ORDER_V1' AND idempotency_key = ?",
                    String::class.java,
                    key,
                ),
            ).isEqualTo("PROCESSING")
            val intendedOrderId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT intended_order_id FROM ordering_idempotency_record " +
                            "WHERE operation = 'REORDER_ORDER_V1' AND idempotency_key = ?",
                        UUID::class.java,
                        key,
                    ),
                )
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM ordering_order WHERE id = ?)",
                    Boolean::class.java,
                    intendedOrderId,
                ),
            ).isFalse()

            jdbcTemplate.update(
                "UPDATE ordering_idempotency_record SET started_at = ? " +
                    "WHERE operation = 'REORDER_ORDER_V1' AND idempotency_key = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(10))),
                key,
            )
            assertThat(worker.runOnce()).isOne()

            val replay = reorderOrder.reorder(key, source.command())
            assertThat(replay.status).isEqualTo(409)
            assertThat(replay.body).contains("\"code\":\"IDEMPOTENCY_MANUAL_REVIEW_REQUIRED\"")
            assertThat(replay.retryAfterSeconds).isNull()
            assertThat(count("ordering_order")).isEqualTo(orderBefore)
            assertThat(manualReviewMetadata(source.fixture.customerId))
                .containsEntry("manual_review_reason", "ORDER_NOT_FOUND")
                .containsEntry("intended_order_exists", false)
        }

        @Test
        fun `fresh processing stays processing until the stuck threshold`() {
            val actorId = UUID.randomUUID()
            insertProcessing(actorId, "fresh-reorder-key", "b".repeat(64), clock.instant())

            assertThat(worker.runOnce()).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT status FROM ordering_idempotency_record WHERE actor_id = ?",
                    String::class.java,
                    actorId,
                ),
            ).isEqualTo("PROCESSING")
            assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
        }

        private fun insertProcessing(
            actorId: UUID,
            key: String,
            payloadHash: String,
            startedAt: java.time.Instant,
            intendedOrderId: UUID = UUID.randomUUID(),
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_idempotency_record (
                    id, actor_id, operation, idempotency_key, payload_hash, status,
                    intended_order_id, started_at
                ) VALUES (?, ?, 'REORDER_ORDER_V1', ?, ?, 'PROCESSING', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                key,
                payloadHash,
                intendedOrderId,
                Timestamp.from(startedAt),
            )
        }

        private fun sourceOrder(): SourceFixture {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            check(createOrder.create("source-create-${UUID.randomUUID()}", fixture.command()).status == 201)
            val orderId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM ordering_order", UUID::class.java))
            jdbcTemplate.update("UPDATE ordering_order SET state = 'EXPIRED' WHERE id = ?", orderId)
            return SourceFixture(fixture, orderId)
        }

        private fun manualReviewMetadata(actorId: UUID): Map<String, Any?> =
            jdbcTemplate.queryForMap(
                "SELECT manual_review_reason, manual_review_started_at, intended_order_exists " +
                    "FROM ordering_idempotency_record WHERE actor_id = ? AND status = 'MANUAL_REVIEW'",
                actorId,
            )

        private fun installFailedTransitionTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_failed_idempotency_transition()
                RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'injected Tx I2 failure';
                END
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_reject_failed_idempotency_transition
                BEFORE UPDATE ON ordering_idempotency_record
                FOR EACH ROW
                WHEN (NEW.status = 'FAILED')
                EXECUTE FUNCTION test_reject_failed_idempotency_transition()
                """.trimIndent(),
            )
        }

        private fun removeFailedTransitionTrigger() {
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS test_reject_failed_idempotency_transition ON ordering_idempotency_record",
            )
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_failed_idempotency_transition()")
        }

        private fun count(table: String): Long = OrderCreationDatabaseFixture.count(jdbcTemplate, table)

        private data class SourceFixture(
            val fixture: OrderCreationFixture,
            val orderId: UUID,
        ) {
            fun command() =
                io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand(
                    customerId = fixture.customerId,
                    sourceOrderId = orderId,
                    pickupSlotId = fixture.pickupSlotId,
                    couponIssuanceId = null,
                    pointsToUseKrw = 0,
                )
        }
    }
