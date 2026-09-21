@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
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
        val index =
            jdbcTemplate.queryForMap(
                """
                SELECT pg_get_indexdef(index_state.indexrelid) AS definition,
                       index_state.indisvalid AS valid,
                       index_state.indisready AS ready
                  FROM pg_index index_state
                 WHERE index_state.indexrelid =
                       to_regclass('public.idx_payment_idempotency_payment_id')
                """.trimIndent(),
            )

        assertThat(index["definition"])
            .isEqualTo(
                "CREATE INDEX idx_payment_idempotency_payment_id " +
                    "ON public.payment_idempotency_record USING btree (payment_id)",
            )
        assertThat(index["valid"]).isEqualTo(true)
        assertThat(index["ready"]).isEqualTo(true)
    }

    @Test
    fun `V92 accepts an equivalent pre-created index and records the migration`() {
        migrateToBeforeV92()
        jdbcTemplate.execute(
            "CREATE INDEX idx_payment_idempotency_payment_id " +
                "ON payment_idempotency_record (payment_id)",
        )

        val result = flyway().migrate()

        assertThat(result.migrationsExecuted).isEqualTo(1)
        assertThat(successfulV92Count()).isEqualTo(1)
    }

    @Test
    fun `V92 rejects a same-named index with a different definition`() {
        migrateToBeforeV92()
        jdbcTemplate.execute(
            "CREATE INDEX idx_payment_idempotency_payment_id " +
                "ON payment_idempotency_record (order_id)",
        )

        assertThatThrownBy { flyway().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("does not match required definition")
        assertThat(successfulV92Count()).isZero()
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

    private fun migrateToBeforeV92() {
        flyway(cleanDisabled = false).clean()
        flyway(target = MigrationVersion.fromVersion("91")).migrate()
    }

    private fun successfulV92Count(): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '92' AND success",
            Int::class.java,
        ) ?: 0

    private fun flyway(
        cleanDisabled: Boolean = true,
        target: MigrationVersion? = null,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)

        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }
}
