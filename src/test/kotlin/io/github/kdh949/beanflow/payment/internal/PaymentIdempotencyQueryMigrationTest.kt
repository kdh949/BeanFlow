@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class PaymentIdempotencyQueryMigrationTest : IsolatedPostgresSupport() {
    companion object {
        const val FIXTURE_RECORD_COUNT = 25_000
        const val PLAN_SCHEMA = "payment_idempotency_query_plan"
        val TARGET_PAYMENT_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
    }

    private val dataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }

    @BeforeEach
    fun resetSchema() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V92 creates the payment idempotency payment index`() {
        val definition =
            jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' " +
                    "AND indexname = 'idx_payment_idempotency_payment_id'",
                String::class.java,
            )

        assertThat(definition).contains("payment_idempotency_record", "(payment_id)")
    }

    @Test
    fun `fixed payment idempotency fixture uses V92 index for payment lookup`() {
        createFixture()
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.payment_idempotency_record")
        val withoutIndex = explain()

        jdbcTemplate.execute(
            "CREATE INDEX idx_payment_idempotency_payment_id " +
                "ON $PLAN_SCHEMA.payment_idempotency_record (payment_id)",
        )
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.payment_idempotency_record")
        val withIndex = explain()

        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("idx_payment_idempotency_payment_id")
        println("PAYMENT_IDEMPOTENCY_QUERY_EXPLAIN_FIXTURE rows=$FIXTURE_RECORD_COUNT matches=1")
        println("PAYMENT_IDEMPOTENCY_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("PAYMENT_IDEMPOTENCY_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun createFixture() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS $PLAN_SCHEMA CASCADE")
        jdbcTemplate.execute("CREATE SCHEMA $PLAN_SCHEMA")
        jdbcTemplate.execute(
            """
            CREATE TABLE $PLAN_SCHEMA.payment_idempotency_record (
                id uuid PRIMARY KEY,
                actor_id uuid NOT NULL,
                operation varchar(80) NOT NULL,
                idempotency_key varchar(128) NOT NULL,
                payload_hash varchar(64) NOT NULL,
                payment_id uuid NOT NULL,
                order_id uuid NOT NULL,
                status varchar(24) NOT NULL,
                response_status integer,
                response_body text,
                started_at timestamptz NOT NULL,
                terminal_at timestamptz,
                version bigint NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO $PLAN_SCHEMA.payment_idempotency_record (
                id, actor_id, operation, idempotency_key, payload_hash,
                payment_id, order_id, status, started_at, version
            )
            SELECT md5('record-' || sequence)::uuid,
                   md5('actor-' || sequence)::uuid,
                   'CONFIRM',
                   'key-' || sequence,
                   repeat('a', 64),
                   CASE WHEN sequence = ? THEN ?::uuid ELSE md5('payment-' || sequence)::uuid END,
                   md5('order-' || sequence)::uuid,
                   'COMPLETED',
                   timestamptz '2026-01-01 00:00:00+00' + sequence * interval '1 second',
                   0
              FROM generate_series(1, ?) AS sequence
            """.trimIndent(),
            FIXTURE_RECORD_COUNT,
            TARGET_PAYMENT_ID,
            FIXTURE_RECORD_COUNT,
        )
    }

    private fun explain(): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, actor_id, operation, idempotency_key, payload_hash, payment_id,
                       order_id, status, response_status, response_body, started_at, terminal_at, version
                  FROM $PLAN_SCHEMA.payment_idempotency_record
                 WHERE payment_id = ?
                """.trimIndent(),
                String::class.java,
                TARGET_PAYMENT_ID,
            ).joinToString("\n")

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}
