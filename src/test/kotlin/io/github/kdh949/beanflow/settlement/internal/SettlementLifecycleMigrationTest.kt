@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Testcontainers
internal class SettlementLifecycleMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToSettlementFoundation() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "27").migrate()
    }

    @Test
    fun `pre-existing closed batch blocks unverified lifecycle summary migration`() {
        val storeId = insertStore()
        jdbcTemplate.update(
            "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                "VALUES (?, ?, '2026-08-03', 'CALCULATED', now(), 0)",
            UUID.randomUUID(),
            storeId,
        )

        assertThatThrownBy { migrateCurrent() }
            .hasStackTraceContaining("pre-existing closed Batch")
            .hasStackTraceContaining("verified summary inventory")

        assertThat(columnCount("settlement_batch", "item_count")).isZero()
        assertThat(tableCount("settlement_adjustment")).isZero()
        assertThat(tableCount("settlement_dispute")).isZero()
    }

    @Test
    fun `batch transition summary and adjustment constraints preserve confirmed ledger`() {
        migrateCurrent()
        val storeId = insertStore()
        val batchId = insertBatch(storeId)
        val orderId = insertSyntheticCompletedOrder(storeId)
        val itemId = insertItem(batchId, orderId, storeId)

        assertThatThrownBy { insertAdjustment(itemId, batchId, storeId, "refund:before-confirmation") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("requires a confirmed Item")
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = now() WHERE id = ?",
                batchId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("OPEN to CALCULATED")

        calculateBatch(batchId)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE settlement_batch SET gross_paid_krw = 999 WHERE id = ?",
                batchId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("CALCULATED to CONFIRMED")
        confirmBatch(batchId)

        val adjustmentId = insertAdjustment(itemId, batchId, storeId, "refund:confirmed")
        assertThatThrownBy { insertAdjustment(itemId, batchId, storeId, "refund:confirmed") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE settlement_adjustment SET amount_krw = amount_krw - 1 WHERE id = ?",
                adjustmentId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementAdjustment is immutable")
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM settlement_adjustment WHERE id = ?", adjustmentId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("SettlementAdjustment is immutable")
        assertThatThrownBy {
            jdbcTemplate.update("UPDATE settlement_batch SET confirmed_at = now() WHERE id = ?", batchId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("Confirmed SettlementBatch is immutable")

        assertThat(indexCount("idx_settlement_batch_store_list")).isOne()
        assertThat(indexCount("idx_settlement_adjustment_next_batch")).isOne()
        assertThat(indexCount("idx_settlement_adjustment_created_cursor")).isOne()
    }

    @Test
    fun `dispute constraints enforce confirmed item active uniqueness and one refile`() {
        migrateCurrent()
        val storeId = insertStore()
        val batchId = insertBatch(storeId)
        val orderId = insertSyntheticCompletedOrder(storeId)
        val itemId = insertItem(batchId, orderId, storeId)

        assertThatThrownBy { insertDispute(itemId, storeId, "evidence:open") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("requires a confirmed Item")

        calculateBatch(batchId)
        confirmBatch(batchId)
        val firstId = insertDispute(itemId, storeId, "evidence:first")

        assertThatThrownBy { insertDispute(itemId, storeId, "evidence:duplicate") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE settlement_dispute SET state = 'REJECTED', held_amount_krw = 0, decided_at = now() " +
                    "WHERE id = ?",
                firstId,
            )
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("enter review")

        jdbcTemplate.update(
            "UPDATE settlement_dispute SET state = 'UNDER_REVIEW', version = version + 1 WHERE id = ?",
            firstId,
        )
        jdbcTemplate.update(
            "UPDATE settlement_dispute SET state = 'REJECTED', held_amount_krw = 0, decided_at = now(), " +
                "version = version + 1 WHERE id = ?",
            firstId,
        )
        assertThatThrownBy { insertDispute(itemId, storeId, "evidence:first", firstId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("refile guard")
        val refileId = insertDispute(itemId, storeId, "evidence:refile", firstId)

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT refile_count FROM settlement_dispute WHERE id = ?",
                Int::class.java,
                refileId,
            ),
        ).isEqualTo(1)
        jdbcTemplate.update(
            "UPDATE settlement_dispute SET state = 'UNDER_REVIEW', version = version + 1 WHERE id = ?",
            refileId,
        )
        jdbcTemplate.update(
            "UPDATE settlement_dispute SET state = 'WITHDRAWN', held_amount_krw = 0, decided_at = now(), " +
                "version = version + 1 WHERE id = ?",
            refileId,
        )
        assertThatThrownBy { insertDispute(itemId, storeId, "evidence:second-refile", refileId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasStackTraceContaining("refile guard")
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM settlement_dispute WHERE id = ?", firstId)
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("append-preserving")

        assertThat(indexCount("uq_settlement_dispute_active_item")).isOne()
        assertThat(indexCount("idx_settlement_dispute_pending_decision")).isOne()
    }

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun insertBatch(storeId: UUID): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                    "VALUES (?, ?, '2026-08-03', 'OPEN', now(), 0)",
                it,
                storeId,
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

    private fun insertItem(
        batchId: UUID,
        orderId: UUID,
        storeId: UUID,
    ): UUID =
        UUID.randomUUID().also { itemId ->
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, '2026-08-03T01:02:03Z', '2026-08-03', 'KRW',
                          1000, 500, 50, 100, 50, 150, 800, now())
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
            )
        }

    private fun calculateBatch(batchId: UUID) {
        jdbcTemplate.update(
            """
            UPDATE settlement_batch
               SET state = 'CALCULATED',
                   item_count = 1,
                   gross_paid_krw = 1000,
                   fee_krw = 50,
                   benefit_cost_krw = 150,
                   item_net_settlement_krw = 800,
                   adjustment_krw = 0,
                   carry_forward_in_krw = 0,
                   carry_forward_out_krw = 0,
                   calculated_at = '2026-08-04T00:00:00Z',
                   version = version + 1
             WHERE id = ?
            """.trimIndent(),
            batchId,
        )
    }

    private fun confirmBatch(batchId: UUID) {
        jdbcTemplate.update(
            "UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = '2026-08-04T00:01:00Z', " +
                "version = version + 1 WHERE id = ?",
            batchId,
        )
    }

    private fun insertAdjustment(
        itemId: UUID,
        batchId: UUID,
        storeId: UUID,
        source: String,
    ): UUID =
        UUID.randomUUID().also { adjustmentId ->
            jdbcTemplate.update(
                """
                INSERT INTO settlement_adjustment (
                    id, store_id, settlement_item_id, source_settlement_batch_id,
                    adjustment_source, reason_code, effective_at, order_completed_at,
                    settlement_date, currency, amount_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, 'REFUND_SUCCEEDED', ?, ?, '2026-08-03', 'KRW', -100, ?)
                """.trimIndent(),
                adjustmentId,
                storeId,
                itemId,
                batchId,
                source,
                Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-03T01:02:03Z")),
                Timestamp.from(Instant.parse("2026-08-05T00:00:01Z")),
            )
        }

    private fun insertDispute(
        itemId: UUID,
        storeId: UUID,
        evidence: String,
        previousDisputeId: UUID? = null,
    ): UUID =
        UUID.randomUUID().also { disputeId ->
            jdbcTemplate.update(
                """
                INSERT INTO settlement_dispute (
                    id, settlement_item_id, store_id, previous_dispute_id, refile_count,
                    state, expected_adjustment_krw, held_amount_krw, reason,
                    evidence_references, actor_id, operation, idempotency_key, payload_hash,
                    response_status, response_body, correlation_id, filed_at, version
                ) VALUES (?, ?, ?, ?, ?, 'FILED', -100, -100, 'amount mismatch',
                          jsonb_build_array(?::text), ?, 'CREATE_SETTLEMENT_DISPUTE_V1', ?, ?,
                          201, '{}', ?, '2026-08-05T00:00:00Z', 0)
                """.trimIndent(),
                disputeId,
                itemId,
                storeId,
                previousDisputeId,
                if (previousDisputeId == null) 0 else 1,
                evidence,
                UUID.randomUUID(),
                "key:$disputeId",
                "a".repeat(64),
                "correlation:$disputeId",
            )
        }

    private fun tableCount(name: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Long::class.java,
                name,
            ),
        )

    private fun columnCount(
        tableName: String,
        columnName: String,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Long::class.java,
                tableName,
                columnName,
            ),
        )

    private fun indexCount(indexName: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                Long::class.java,
                indexName,
            ),
        )

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
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}
