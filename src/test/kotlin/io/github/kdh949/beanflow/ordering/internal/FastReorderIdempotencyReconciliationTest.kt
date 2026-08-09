package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
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
@SpringBootTest
internal class FastReorderIdempotencyReconciliationTest @Autowired constructor(
    private val worker: OrderIdempotencyReconciliationWorker,
    private val idempotency: OrderIdempotencyService,
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
        assertThat(OrderCreationDatabaseFixture.count(jdbcTemplate, "ordering_order")).isZero()
        assertThat(
            idempotency.register(
                actorId = actorId,
                operation = OrderCreationOperation.REORDER,
                idempotencyKey = key,
                payloadHash = payloadHash,
                intendedOrderId = UUID.randomUUID(),
            ),
        ).isEqualTo(IdempotencyRegistration.InProgress)
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
            UUID.randomUUID(),
            Timestamp.from(startedAt),
        )
    }
}
