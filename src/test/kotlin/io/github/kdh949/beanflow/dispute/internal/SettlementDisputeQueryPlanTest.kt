@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.dispute.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * The store dispute list orders by `filed_at DESC, id DESC`, which V28's
 * ascending `idx_settlement_dispute_store_filed (store_id, filed_at, id)` can
 * serve with a backward scan. This measures that on real PostgreSQL so no
 * duplicate DESC index is added on assumption.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class SettlementDisputeQueryPlanTest {
    companion object {
        private const val ROW_COUNT = 20_000
        private const val LIMIT = 20
        private const val INDEX_NAME = "idx_settlement_dispute_store_filed"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun migrate() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `the existing store index serves the newest-first page as a backward index scan`() {
        val storeId = UUID.fromString("5d000000-0000-0000-0000-000000000001")
        seed(storeId)
        jdbcTemplate.execute("ANALYZE settlement_dispute")

        val withIndex = explain(storeId)
        jdbcTemplate.execute("DROP INDEX $INDEX_NAME")
        jdbcTemplate.execute("ANALYZE settlement_dispute")
        val withoutIndex = explain(storeId)

        assertThat(withIndex).contains("Index Scan Backward using $INDEX_NAME")
        assertThat(withIndex).doesNotContain("Sort Method")
        assertThat(withoutIndex).contains("Seq Scan on settlement_dispute")

        println("SETTLEMENT_DISPUTE_EXPLAIN_FIXTURE rows=$ROW_COUNT limit=$LIMIT")
        println("SETTLEMENT_DISPUTE_WITH_INDEX\n$withIndex")
        println("SETTLEMENT_DISPUTE_WITHOUT_INDEX\n$withoutIndex")
    }

    private fun explain(storeId: UUID): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, settlement_item_id, state, expected_adjustment_krw,
                       held_amount_krw, filed_at, decided_at
                  FROM settlement_dispute
                 WHERE store_id = ?
                 ORDER BY filed_at DESC, id DESC
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
                storeId,
            ).joinToString("\n")

    /**
     * Seeds through the same guards production writes use: an OPEN Batch per
     * settlement day, one Item, then CALCULATED and CONFIRMED before the
     * dispute row.
     */
    private fun seed(targetStoreId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            targetStoreId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version)
            SELECT md5('dispute-plan-store:' || series)::uuid, true, true, 0
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO ordering_public_reference_registry (public_reference, allocated_at)
            SELECT 'BF-' || upper(translate(lpad(to_hex(series), 4, '2'), '01', 'yz'))
                   || '-' || upper(translate(lpad(to_hex(series + 70000), 4, '2'), '01', 'yz')),
                   timestamptz '2026-08-12 00:00:00+00'
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
        )
        jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
        try {
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (
                    id, customer_id, store_id, pickup_slot_id,
                    public_reference, pickup_business_date, pickup_sequence,
                    store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                    state, subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                    currency, paid_at, acceptance_warning_at, acceptance_deadline_at,
                    accepted_at, preparing_at, ready_at, completed_at, created_at, updated_at, version
                )
                SELECT md5('dispute-plan-order:' || series)::uuid,
                       md5('dispute-plan-customer:' || series)::uuid,
                       CASE WHEN series % 2 = 0 THEN ?::uuid ELSE md5('dispute-plan-store:' || series)::uuid END,
                       md5('dispute-plan-slot:' || series)::uuid,
                       'BF-' || upper(translate(lpad(to_hex(series), 4, '2'), '01', 'yz'))
                   || '-' || upper(translate(lpad(to_hex(series + 70000), 4, '2'), '01', 'yz')),
                       DATE '2026-08-03', series,
                       'Plan Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                       'COMPLETED', 1000, 100, 100, 800, 'KRW',
                       timestamptz '2026-08-03 00:10:00+00', timestamptz '2026-08-03 00:12:00+00',
                       timestamptz '2026-08-03 00:13:00+00', timestamptz '2026-08-03 00:11:00+00',
                       timestamptz '2026-08-03 00:12:00+00', timestamptz '2026-08-03 00:13:00+00',
                       timestamptz '2026-09-01 01:00:00+00' + series * interval '1 day',
                       timestamptz '2026-08-03 00:00:00+00',
                       timestamptz '2026-09-01 01:00:00+00' + series * interval '1 day',
                       7
                  FROM generate_series(1, $ROW_COUNT) AS series
                """.trimIndent(),
                targetStoreId,
            )
        } finally {
            jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
        }
        jdbcTemplate.update(
            """
            INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version)
            SELECT md5('dispute-plan-batch:' || series)::uuid,
                   CASE WHEN series % 2 = 0 THEN ?::uuid ELSE md5('dispute-plan-store:' || series)::uuid END,
                   ((timestamptz '2026-09-01 01:00:00+00' + series * interval '1 day')
                        AT TIME ZONE 'Asia/Seoul')::date,
                   'OPEN', now(), 0
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetStoreId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO settlement_item (
                id, settlement_batch_id, order_id, store_id, item_source,
                completed_at, settlement_date, currency,
                gross_paid_krw, fee_rate_bps, fee_krw,
                coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                net_settlement_krw, created_at
            )
            SELECT md5('dispute-plan-item:' || series)::uuid,
                   md5('dispute-plan-batch:' || series)::uuid,
                   md5('dispute-plan-order:' || series)::uuid,
                   CASE WHEN series % 2 = 0 THEN ?::uuid ELSE md5('dispute-plan-store:' || series)::uuid END,
                   'order:' || series || ':completed:7',
                   timestamptz '2026-09-01 01:00:00+00' + series * interval '1 day',
                   ((timestamptz '2026-09-01 01:00:00+00' + series * interval '1 day')
                        AT TIME ZONE 'Asia/Seoul')::date,
                   'KRW', 1000, 500, 50, 100, 50, 150, 800, now()
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetStoreId,
        )
        jdbcTemplate.update(
            """
            UPDATE settlement_batch
               SET state = 'CALCULATED', item_count = 1, gross_paid_krw = 1000, fee_krw = 50,
                   benefit_cost_krw = 150, item_net_settlement_krw = 800, adjustment_krw = 0,
                   carry_forward_in_krw = 0, carry_forward_out_krw = 0, calculated_at = now(),
                   version = version + 1
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "UPDATE settlement_batch SET state = 'CONFIRMED', confirmed_at = now(), version = version + 1",
        )
        jdbcTemplate.update(
            """
            INSERT INTO settlement_dispute (
                id, settlement_item_id, store_id, previous_dispute_id, refile_count,
                state, expected_adjustment_krw, held_amount_krw, reason, evidence_references,
                actor_id, operation, idempotency_key, payload_hash,
                response_status, response_body, correlation_id, filed_at, version
            )
            SELECT md5('dispute-plan-dispute:' || series)::uuid,
                   md5('dispute-plan-item:' || series)::uuid,
                   CASE WHEN series % 2 = 0 THEN ?::uuid ELSE md5('dispute-plan-store:' || series)::uuid END,
                   NULL, 0, 'FILED', 500, 500, 'query plan fixture',
                   ('["evidence:' || series || '"]')::jsonb,
                   md5('dispute-plan-actor:' || series)::uuid,
                   'SETTLEMENT_DISPUTE_FILE', 'plan-key-' || series, repeat('a', 64),
                   201, '{}', 'plan-correlation-' || series,
                   timestamptz '2026-09-10 00:00:00+00' + series * interval '1 second',
                   0
              FROM generate_series(1, $ROW_COUNT) AS series
            """.trimIndent(),
            targetStoreId,
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}
