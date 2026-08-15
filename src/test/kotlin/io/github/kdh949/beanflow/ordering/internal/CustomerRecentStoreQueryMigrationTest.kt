@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.ordering.internal

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class CustomerRecentStoreQueryMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        const val FIXTURE_ORDER_COUNT = 20_000
        const val RELEVANT_ORDER_COUNT = 500
        const val LIMIT = 20
        const val PLAN_SCHEMA = "customer_recent_store_query_plan"
        val CUSTOMER_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
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
    fun `V63 creates the customer recent-store state index`() {
        val definition =
            jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = " +
                    "'ix_ordering_order_customer_recent_store'",
                String::class.java,
            )

        assertThat(definition).contains("customer_id", "state", "created_at DESC", "store_id")
    }

    @Test
    fun `fixed recent-store fixture records the grouped BR-40 plan before and after V63 index`() {
        insertOrders()
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")

        val withoutIndex = explain()
        jdbcTemplate.execute(
            "CREATE INDEX ix_ordering_order_customer_recent_store " +
                "ON $PLAN_SCHEMA.ordering_order (customer_id, state, created_at DESC, store_id)",
        )
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")
        val withIndex = explain()

        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("ix_ordering_order_customer_recent_store")
        println("CUSTOMER_RECENT_STORE_QUERY_EXPLAIN_FIXTURE rows=$FIXTURE_ORDER_COUNT relevant=$RELEVANT_ORDER_COUNT limit=$LIMIT")
        println("CUSTOMER_RECENT_STORE_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("CUSTOMER_RECENT_STORE_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun insertOrders() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS $PLAN_SCHEMA CASCADE")
        jdbcTemplate.execute("CREATE SCHEMA $PLAN_SCHEMA")
        jdbcTemplate.execute(
            """
            CREATE TABLE $PLAN_SCHEMA.ordering_order (
                id uuid PRIMARY KEY,
                customer_id uuid NOT NULL,
                store_id uuid NOT NULL,
                state varchar(32) NOT NULL,
                created_at timestamptz NOT NULL
            )
            """.trimIndent(),
        )
        val eligibleStates = listOf("PAID", "ACCEPTED", "PREPARING", "READY", "COMPLETED")
        val orders =
            (0 until FIXTURE_ORDER_COUNT).map { sequence ->
                val relevant = sequence < RELEVANT_ORDER_COUNT
                arrayOf(
                    UUID.nameUUIDFromBytes("recent-store-order:$sequence".toByteArray()),
                    if (relevant) CUSTOMER_ID else UUID.nameUUIDFromBytes("other-customer:${sequence % 100}".toByteArray()),
                    UUID.nameUUIDFromBytes("recent-store:${sequence % 50}".toByteArray()),
                    if (relevant) eligibleStates[sequence % eligibleStates.size] else "EXPIRED",
                    Timestamp.from(FIXTURE_START.minusSeconds(sequence.toLong())),
                )
            }
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.ordering_order (id, customer_id, store_id, state, created_at) VALUES (?, ?, ?, ?, ?)",
            orders,
        )
    }

    private fun explain(): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT store_id, max(created_at) AS last_ordered_at
                  FROM $PLAN_SCHEMA.ordering_order
                 WHERE customer_id = ?
                   AND state IN ('PAID', 'ACCEPTED', 'PREPARING', 'READY', 'COMPLETED')
                 GROUP BY store_id
                 ORDER BY last_ordered_at DESC, store_id ASC
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
                CUSTOMER_ID,
            ).joinToString("\n")

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}
