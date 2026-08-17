@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class FastReorderMigrationTest : IsolatedPostgresSupport() {
    companion object {
        val COMPLETED_AT: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToPreFastReorderSchema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "35").migrate()
    }

    @Test
    fun `V36 marks existing lines legacy and backfills terminal retention to exactly ninety days`() {
        val orderId = insertSyntheticOrder()
        val lineId = insertLegacyLine(orderId)
        val recordId = insertTerminalIdempotency()

        migrateCurrent()

        val line =
            jdbcTemplate.queryForMap(
                "SELECT option_selection_snapshot_state, normalized_option_ids_json " +
                    "FROM ordering_order_line WHERE id = ?",
                lineId,
            )
        assertThat(line["option_selection_snapshot_state"]).isEqualTo("LEGACY_UNAVAILABLE")
        assertThat(line["normalized_option_ids_json"]).isNull()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_expires_at FROM ordering_idempotency_record WHERE id = ?",
                Instant::class.java,
                recordId,
            ),
        ).isEqualTo(COMPLETED_AT.plus(Duration.ofDays(90)))
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_ordering_idempotency_terminal_retention'",
                Long::class.java,
            ),
        ).isOne()
    }

    @Test
    fun `V36 rejects invalid option snapshot state and terminal retention combinations`() {
        migrateCurrent()
        val orderId = insertSyntheticOrder()

        assertThatThrownBy {
            insertLine(orderId, "SNAPSHOTTED", null)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(orderId, "LEGACY_UNAVAILABLE", "[]")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(orderId, "SNAPSHOTTED", "{}")
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            insertLine(orderId, "SNAPSHOTTED", "[\"not-a-uuid\"]")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(orderId, "SNAPSHOTTED", "[1]")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(orderId, "SNAPSHOTTED", "[\"00000000-0000-0000-0000-00000000000A\"]")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(
                orderId,
                "SNAPSHOTTED",
                "[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000001\"]",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertLine(
                orderId,
                "SNAPSHOTTED",
                "[\"00000000-0000-0000-0000-000000000002\",\"00000000-0000-0000-0000-000000000001\"]",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        insertLine(orderId, "SNAPSHOTTED", "[]")
        insertLine(
            orderId,
            "SNAPSHOTTED",
            "[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000002\"]",
        )
        insertLine(
            orderId,
            "SNAPSHOTTED",
            "[\"7fffffff-ffff-ffff-ffff-ffffffffffff\",\"80000000-0000-0000-0000-000000000000\"]",
        )

        assertThatThrownBy {
            insertTerminalIdempotency(retentionExpiresAt = COMPLETED_AT.plus(Duration.ofDays(89)))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V36 fails closed when a terminal idempotency row has no completion time`() {
        val recordId = insertTerminalIdempotency()
        val terminalConstraint =
            jdbcTemplate.queryForObject(
                """
                SELECT conname
                  FROM pg_constraint
                 WHERE conrelid = 'ordering_idempotency_record'::regclass
                   AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%response_status%'
                """.trimIndent(),
                String::class.java,
            )
        jdbcTemplate.execute("ALTER TABLE ordering_idempotency_record DROP CONSTRAINT $terminalConstraint")
        jdbcTemplate.update(
            "UPDATE ordering_idempotency_record SET completed_at = NULL WHERE id = ?",
            recordId,
        )

        assertThatThrownBy { migrateCurrent() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("terminal row without completed_at")
    }

    @Test
    fun `V36 rejects malformed duplicate and unsorted merchant configuration keys`() {
        migrateCurrent()
        val storeId = UUID.randomUUID()
        val menuId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)",
            storeId,
        )
        jdbcTemplate.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) " +
                "VALUES (?, ?, 'Menu', 1000, true)",
            menuId,
            storeId,
        )

        insertConfiguration(menuId, "")
        insertConfiguration(menuId, "00000000-0000-0000-0000-000000000001")
        assertThatThrownBy { insertConfiguration(menuId, "not-a-uuid") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertConfiguration(menuId, "00000000-0000-0000-0000-00000000000A") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertConfiguration(
                menuId,
                "00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000001",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertConfiguration(
                menuId,
                "00000000-0000-0000-0000-000000000002,00000000-0000-0000-0000-000000000001",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertSyntheticOrder(): UUID {
        val orderId = UUID.randomUUID()
        jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id, state,
                    subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, reservation_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'EXPIRED', 1000, 0, 0, 1000, 'KRW', ?, ?, ?)
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(COMPLETED_AT.plusSeconds(300)),
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(COMPLETED_AT),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        return orderId
    }

    private fun insertLegacyLine(orderId: UUID): UUID {
        val lineId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_line (
                id, order_id, line_sequence, menu_id, menu_name, option_names_json,
                sellable_requirements_json, unit_price_krw, quantity, gross_krw,
                coupon_discount_krw, points_applied_krw, cash_payable_krw
            ) VALUES (?, ?, 0, ?, 'Legacy menu', '[]', '[]', 1000, 1, 1000, 0, 0, 1000)
            """.trimIndent(),
            lineId,
            orderId,
            UUID.randomUUID(),
        )
        return lineId
    }

    private fun insertLine(
        orderId: UUID,
        state: String,
        normalizedOptionIdsJson: String?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO ordering_order_line (
                id, order_id, line_sequence, menu_id, menu_name, option_names_json,
                sellable_requirements_json, unit_price_krw, quantity, gross_krw,
                coupon_discount_krw, points_applied_krw, cash_payable_krw,
                option_selection_snapshot_state, normalized_option_ids_json
            ) VALUES (?, ?, ?, ?, 'Current menu', '[]', '[]', 1000, 1, 1000, 0, 0, 1000, ?, ?::jsonb)
            """.trimIndent(),
            UUID.randomUUID(),
            orderId,
            UUID.randomUUID().hashCode().and(Int.MAX_VALUE),
            UUID.randomUUID(),
            state,
            normalizedOptionIdsJson,
        )
    }

    private fun insertTerminalIdempotency(retentionExpiresAt: Instant? = null): UUID {
        val id = UUID.randomUUID()
        if (retentionExpiresAt == null) {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_idempotency_record (
                    id, actor_id, operation, idempotency_key, payload_hash, status,
                    intended_order_id, response_status, response_body, response_version,
                    started_at, completed_at
                ) VALUES (?, ?, 'CREATE_ORDER', ?, ?, 'COMPLETED', ?, 201, '{}', 1, ?, ?)
                """.trimIndent(),
                id,
                UUID.randomUUID(),
                "fast-reorder-${UUID.randomUUID()}",
                "a".repeat(64),
                UUID.randomUUID(),
                Timestamp.from(COMPLETED_AT.minusSeconds(1)),
                Timestamp.from(COMPLETED_AT),
            )
        } else {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_idempotency_record (
                    id, actor_id, operation, idempotency_key, payload_hash, status,
                    intended_order_id, response_status, response_body, response_version,
                    started_at, completed_at, retention_expires_at
                ) VALUES (?, ?, 'REORDER_ORDER_V1', ?, ?, 'FAILED', ?, 409, '{}', 1, ?, ?, ?)
                """.trimIndent(),
                id,
                UUID.randomUUID(),
                "fast-reorder-${UUID.randomUUID()}",
                "b".repeat(64),
                UUID.randomUUID(),
                Timestamp.from(COMPLETED_AT.minusSeconds(1)),
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(retentionExpiresAt),
            )
        }
        return id
    }

    private fun insertConfiguration(
        menuId: UUID,
        normalizedOptionKey: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO merchant_menu_configuration (id, menu_id, normalized_option_key, available) " +
                "VALUES (?, ?, ?, true)",
            UUID.randomUUID(),
            menuId,
            normalizedOptionKey,
        )
    }

    private fun migrateCurrent() {
        flyway(target = "36").migrate()
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
