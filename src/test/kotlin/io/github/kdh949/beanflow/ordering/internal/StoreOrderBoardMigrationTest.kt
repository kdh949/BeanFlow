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
internal class StoreOrderBoardMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        const val PLAN_SCHEMA = "store_order_board_plan"
        const val FIXTURE_ORDER_COUNT = 20_000
        const val WRITE_SAMPLE_SIZE = 1_000
        val TARGET_STORE_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000060")
        val OTHER_STORE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000060")
        val START: Instant = Instant.parse("2030-01-01T00:00:00Z")
    }

    private val dataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }

    @BeforeEach
    fun resetSchema() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V56 creates both store board indexes with the paid partial predicate`() {
        val definitions =
            jdbcTemplate
                .query(
                    "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' " +
                        "AND indexname IN ('ix_ordering_order_store_board', 'ix_ordering_order_store_acceptance_board')",
                ) { resultSet, _ -> resultSet.getString("indexname") to resultSet.getString("indexdef") }
                .toMap()
        assertThat(definitions).hasSize(2)
        assertThat(definitions.getValue("ix_ordering_order_store_board"))
            .contains("store_id", "state", "pickup_window_start_snapshot", "id")
        assertThat(definitions.getValue("ix_ordering_order_store_acceptance_board"))
            .contains("store_id", "state", "acceptance_deadline_at", "id", "WHERE")
            .contains("'PAID'")
    }

    @Test
    fun `fixed board fixture records query plans and write cost before and after both indexes`() {
        createPlanTable()
        insertPlanOrders(FIXTURE_ORDER_COUNT)
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")

        val activeWithoutIndex = explainActiveBoard()
        val paidWithoutIndex = explainAcceptanceBoard()
        val writeWithoutIndex = measureWrites("write_without_index")

        createIndexes("ordering_order", "fixture")
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")
        val activeWithIndex = explainActiveBoard()
        val paidWithIndex = explainAcceptanceBoard()
        val writeWithIndex = measureWrites("write_with_index", indexed = true)

        assertThat(activeWithoutIndex).contains("Seq Scan")
        assertThat(paidWithoutIndex).contains("Seq Scan")
        assertThat(activeWithIndex).contains("ix_board_fixture")
        assertThat(paidWithIndex).contains("ix_acceptance_fixture")
        assertThat(writeWithoutIndex.insertNanos).isPositive()
        assertThat(writeWithoutIndex.transitionNanos).isPositive()
        assertThat(writeWithIndex.insertNanos).isPositive()
        assertThat(writeWithIndex.transitionNanos).isPositive()

        println("STORE_ORDER_BOARD_EXPLAIN_FIXTURE rows=$FIXTURE_ORDER_COUNT limit=50")
        println("STORE_ORDER_BOARD_ACTIVE_WITHOUT_INDEX\n$activeWithoutIndex")
        println("STORE_ORDER_BOARD_ACTIVE_WITH_INDEX\n$activeWithIndex")
        println("STORE_ORDER_BOARD_ACCEPTANCE_WITHOUT_INDEX\n$paidWithoutIndex")
        println("STORE_ORDER_BOARD_ACCEPTANCE_WITH_INDEX\n$paidWithIndex")
        println(
            "STORE_ORDER_BOARD_WRITE_SAMPLE rows=$WRITE_SAMPLE_SIZE " +
                "without_insert_ns=${writeWithoutIndex.insertNanos} " +
                "without_transition_ns=${writeWithoutIndex.transitionNanos} " +
                "with_insert_ns=${writeWithIndex.insertNanos} " +
                "with_transition_ns=${writeWithIndex.transitionNanos}",
        )
    }

    private fun createPlanTable() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS $PLAN_SCHEMA CASCADE")
        jdbcTemplate.execute("CREATE SCHEMA $PLAN_SCHEMA")
        jdbcTemplate.execute(
            """
            CREATE TABLE $PLAN_SCHEMA.ordering_order (
                id uuid PRIMARY KEY,
                store_id uuid NOT NULL,
                state varchar(32) NOT NULL,
                pickup_window_start_snapshot timestamptz NOT NULL,
                acceptance_deadline_at timestamptz
            )
            """.trimIndent(),
        )
    }

    private fun insertPlanOrders(count: Int) {
        val rows =
            (0 until count).map { sequence ->
                val target = sequence % 100 == 0
                val state = if (target) listOf("PAID", "ACCEPTED", "PREPARING", "READY")[(sequence / 100) % 4] else "COMPLETED"
                val pickup = START.plusSeconds(sequence.toLong())
                arrayOf<Any>(
                    UUID.nameUUIDFromBytes("store-board-plan:$sequence".toByteArray()),
                    if (target) TARGET_STORE_ID else OTHER_STORE_ID,
                    state,
                    Timestamp.from(pickup),
                    Timestamp.from(pickup.plusSeconds(180)),
                )
            }
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.ordering_order " +
                "(id, store_id, state, pickup_window_start_snapshot, acceptance_deadline_at) VALUES (?, ?, ?, ?, ?)",
            rows,
        )
    }

    private fun createIndexes(
        table: String,
        suffix: String,
    ) {
        jdbcTemplate.execute(
            "CREATE INDEX ix_board_$suffix ON $PLAN_SCHEMA.$table " +
                "(store_id, state, pickup_window_start_snapshot, id)",
        )
        jdbcTemplate.execute(
            "CREATE INDEX ix_acceptance_$suffix ON $PLAN_SCHEMA.$table " +
                "(store_id, state, acceptance_deadline_at, id) WHERE state = 'PAID'",
        )
    }

    private fun explainActiveBoard(): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, state, pickup_window_start_snapshot
                  FROM $PLAN_SCHEMA.ordering_order
                 WHERE store_id = ? AND state = 'READY'
                 ORDER BY pickup_window_start_snapshot, id
                 LIMIT 50
                """.trimIndent(),
                String::class.java,
                TARGET_STORE_ID,
            ).joinToString("\n")

    private fun explainAcceptanceBoard(): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id, state, acceptance_deadline_at
                  FROM $PLAN_SCHEMA.ordering_order
                 WHERE store_id = ? AND state = 'PAID'
                 ORDER BY acceptance_deadline_at, id
                 LIMIT 50
                """.trimIndent(),
                String::class.java,
                TARGET_STORE_ID,
            ).joinToString("\n")

    private fun measureWrites(
        table: String,
        indexed: Boolean = false,
    ): WriteMeasurement {
        jdbcTemplate.execute(
            "CREATE TABLE $PLAN_SCHEMA.$table (LIKE $PLAN_SCHEMA.ordering_order INCLUDING ALL)",
        )
        if (indexed) createIndexes(table, table)
        val rows =
            (0 until WRITE_SAMPLE_SIZE).map { sequence ->
                arrayOf<Any>(
                    UUID.nameUUIDFromBytes("$table:$sequence".toByteArray()),
                    TARGET_STORE_ID,
                    "PAID",
                    Timestamp.from(START.plusSeconds(sequence.toLong())),
                    Timestamp.from(START.plusSeconds(sequence + 180L)),
                )
            }
        val insertStart = System.nanoTime()
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.$table " +
                "(id, store_id, state, pickup_window_start_snapshot, acceptance_deadline_at) VALUES (?, ?, ?, ?, ?)",
            rows,
        )
        val insertNanos = System.nanoTime() - insertStart
        val transitionStart = System.nanoTime()
        jdbcTemplate.update("UPDATE $PLAN_SCHEMA.$table SET state = 'ACCEPTED' WHERE store_id = ?", TARGET_STORE_ID)
        val transitionNanos = System.nanoTime() - transitionStart
        return WriteMeasurement(insertNanos, transitionNanos)
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()

    private data class WriteMeasurement(
        val insertNanos: Long,
        val transitionNanos: Long,
    )
}
