@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class CustomerCancellationMigrationTest : IsolatedPostgresSupport() {
    companion object {
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToPlan30Schema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "22").migrate()
    }

    @Test
    fun `V23 backfills store command retention from original creation time`() {
        val storeId = insertStore()
        val orderId = insertPlan30PaidOrder(storeId)
        val createdAt = Instant.now().minus(Duration.ofDays(100)).truncatedTo(ChronoUnit.MICROS)
        val recordId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO ordering_store_command_idempotency (
                id, actor_id, order_id, operation, idempotency_key, payload_hash,
                response_status, response_body, created_at
            ) VALUES (?, ?, ?, 'STORE_ORDER_TRANSITION_V2', 'store-key-001', ?, 200, '{}', ?)
            """.trimIndent(),
            recordId,
            UUID.randomUUID(),
            orderId,
            "a".repeat(64),
            Timestamp.from(createdAt),
        )

        // This case asserts V23's own backfill and must seed a store before migrating. Migrating to
        // head instead would stop at the V33 discovery-profile coverage gate, which is the intended
        // fail-closed behaviour for a store without a verified profile.
        flyway(target = "23").migrate()

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_expires_at FROM ordering_store_command_idempotency WHERE id = ?",
                Instant::class.java,
                recordId,
            ),
        ).isEqualTo(createdAt.plus(Duration.ofDays(90)))
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'ordering_store_command_idempotency'
                  AND column_name = 'retention_expires_at'
                  AND is_nullable = 'NO'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertPlan30PaidOrder(storeId: UUID): UUID {
        val orderId = UUID.randomUUID()
        jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, paid_at, acceptance_warning_at,
                    acceptance_deadline_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'PAID', 1000, 0, 0, 1000, 'KRW', NULL,
                          ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                storeId,
                UUID.randomUUID(),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(2 * 60)),
                Timestamp.from(now.plusSeconds(3 * 60)),
                Timestamp.from(now),
                Timestamp.from(now),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        return orderId
    }

    private fun flyway(
        target: String? = null,
        cleanDisabled: Boolean = true,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}
