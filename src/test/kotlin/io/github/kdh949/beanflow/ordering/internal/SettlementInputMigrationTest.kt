@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
internal class SettlementInputMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )
    }

    @BeforeEach
    fun resetToPreActivationSchema() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "19").migrate()
    }

    @Test
    fun `existing legacy order blocks activation without guessed settlement sources`() {
        insertSyntheticOrder()

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("Settlement input activation failed")

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_name = 'ordering_order_settlement_input_snapshot'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `clean activation installs exactly-one trigger checks and source indexes`() {
        migrateCurrent()

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM pg_trigger
                WHERE tgname IN (
                    'ordering_order_requires_settlement_input_snapshot',
                    'ordering_settlement_input_snapshot_complete',
                    'ordering_settlement_input_snapshot_immutable'
                )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(3)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'ordering_order_settlement_input_snapshot'
                  AND indexname IN (
                      'idx_order_settlement_terms_source',
                      'idx_order_settlement_coupon_source',
                      'idx_order_settlement_point_source'
                  )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(3)
    }

    @Test
    fun `database rejects a fee tie-out mismatch and immutable snapshot mutation`() {
        migrateCurrent()
        val orderId = insertSyntheticOrder()

        assertThatThrownBy {
            insertSyntheticSnapshot(orderId, feeKrw = 49, disableTriggers = false)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        insertSyntheticSnapshot(orderId, feeKrw = 50, disableTriggers = true)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                UPDATE ordering_order_settlement_input_snapshot
                   SET store_settlement_terms_source_reference = 'test:changed'
                 WHERE order_id = ?
                """.trimIndent(),
                orderId,
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
                ) VALUES (
                    ?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 0, 0, 1000,
                    'KRW', now() + interval '5 minutes', now(), now()
                )
                """.trimIndent(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        return orderId
    }

    private fun insertSyntheticSnapshot(
        orderId: UUID,
        feeKrw: Long,
        disableTriggers: Boolean,
    ) {
        if (disableTriggers) {
            jdbcTemplate.execute("ALTER TABLE ordering_order_settlement_input_snapshot DISABLE TRIGGER USER")
        }
        try {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order_settlement_input_snapshot (
                    order_id, store_id, store_settlement_terms_version_id,
                    store_settlement_terms_source_reference,
                    coupon_discount_krw, platform_coupon_cost_krw, coupon_cost_krw,
                    points_applied_krw, point_cost_krw,
                    gross_paid_krw, fee_base_krw, fee_rate_bps, fee_krw,
                    benefit_cost_krw, net_settlement_krw, currency,
                    snapshot_schema_version, canonical_snapshot_hash, created_at
                ) SELECT
                    id, store_id, ?, 'test:synthetic-terms',
                    0, 0, 0, 0, 0, 1000, 1000, 500, ?, 0, 950, 'KRW',
                    1, ?, created_at
                  FROM ordering_order
                 WHERE id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                feeKrw,
                "a".repeat(64),
                orderId,
            )
        } finally {
            if (disableTriggers) {
                jdbcTemplate.execute("ALTER TABLE ordering_order_settlement_input_snapshot ENABLE TRIGGER USER")
            }
        }
    }

    private fun migrateCurrent() {
        flyway().migrate()
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
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }
}
