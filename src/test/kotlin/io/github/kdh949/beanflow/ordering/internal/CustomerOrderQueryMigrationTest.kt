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
import java.time.Instant
import java.util.UUID

internal class CustomerOrderQueryMigrationTest : IsolatedPostgresSupport() {
    companion object {
        const val FIXTURE_ORDER_COUNT = 10_000
        const val LIMIT = 101
        const val PLAN_SCHEMA = "customer_order_query_plan"
        val CUSTOMER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val FIXTURE_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private val dataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }

    @BeforeEach
    fun resetSchema() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V55 creates the customer recent order keyset index`() {
        val definition =
            jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = " +
                    "'ix_ordering_order_customer_recent'",
                String::class.java,
            )

        assertThat(definition).contains("customer_id", "created_at DESC", "id DESC")
    }

    @Test
    fun `fixed customer fixture records the actual keyset plan before and after V55 index`() {
        insertOrders()
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")

        val withoutIndex = explain()
        jdbcTemplate.execute(
            "CREATE INDEX ix_ordering_order_customer_recent " +
                "ON $PLAN_SCHEMA.ordering_order (customer_id, created_at DESC, id DESC)",
        )
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")
        val withIndex = explain()

        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("Index Scan using ix_ordering_order_customer_recent")
        println("CUSTOMER_ORDER_QUERY_EXPLAIN_FIXTURE rows=$FIXTURE_ORDER_COUNT limit=$LIMIT")
        println("CUSTOMER_ORDER_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("CUSTOMER_ORDER_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun insertOrders() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS $PLAN_SCHEMA CASCADE")
        jdbcTemplate.execute("CREATE SCHEMA $PLAN_SCHEMA")
        jdbcTemplate.execute(
            """
            CREATE TABLE $PLAN_SCHEMA.ordering_order (
                id uuid PRIMARY KEY,
                customer_id uuid NOT NULL,
                state varchar(32) NOT NULL,
                created_at timestamptz NOT NULL
            )
            """.trimIndent(),
        )
        val orders =
            (0 until FIXTURE_ORDER_COUNT).map { sequence ->
                arrayOf(
                    UUID.nameUUIDFromBytes("customer-order-query:$sequence".toByteArray()),
                    CUSTOMER_ID,
                    Timestamp.from(FIXTURE_START.minusSeconds(sequence.toLong())),
                )
            }
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.ordering_order (id, customer_id, state, created_at) " +
                "VALUES (?, ?, 'EXPIRED', ?)",
            orders,
        )
    }

    private fun explain(): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, created_at, state
                  FROM $PLAN_SCHEMA.ordering_order
                 WHERE customer_id = ?
                   AND created_at >= ?
                   AND created_at < ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
                CUSTOMER_ID,
                Timestamp.from(FIXTURE_START.minusSeconds(FIXTURE_ORDER_COUNT.toLong() + 1)),
                Timestamp.from(FIXTURE_START.plusSeconds(1)),
            ).joinToString("\n")

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}
