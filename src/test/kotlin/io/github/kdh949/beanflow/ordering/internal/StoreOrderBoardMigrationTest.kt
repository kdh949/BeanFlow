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
import java.time.LocalDate
import java.util.UUID

internal class StoreOrderBoardMigrationTest : IsolatedPostgresSupport() {
    companion object {
        const val PLAN_SCHEMA = "store_order_board_plan"
        const val FIXTURE_ORDER_COUNT = 20_000
        const val ACTIVE_ROWS_PER_LANE = 200
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
    fun `fixed mixed-lane production query records plans and write cost before and after both indexes`() {
        createPlanTable()
        insertPlanOrders(FIXTURE_ORDER_COUNT)
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")

        val primaryWithoutIndex = explainPrimaryBoard()
        val overflowCountWithoutIndex = explainOverflowCount()
        val overflowPageWithoutIndex = explainOverflowPage()
        val writeWithoutIndex = measureWrites("write_without_index")

        createIndexes("ordering_order", "fixture")
        jdbcTemplate.execute("ANALYZE $PLAN_SCHEMA.ordering_order")
        val primaryWithIndex = explainPrimaryBoard()
        val overflowCountWithIndex = explainOverflowCount()
        val overflowPageWithIndex = explainOverflowPage()
        val writeWithIndex = measureWrites("write_with_index", indexed = true)

        assertThat(primaryWithoutIndex).contains("Seq Scan")
        assertThat(primaryWithIndex).contains("ix_board_fixture", "ix_acceptance_fixture")
        assertThat(overflowPageWithoutIndex).contains("Seq Scan")
        assertThat(overflowPageWithIndex).contains("ix_acceptance_fixture")
        assertThat(overflowCountWithoutIndex).isNotBlank()
        assertThat(overflowCountWithIndex).isNotBlank()
        assertThat(writeWithoutIndex.insertNanos).isPositive()
        assertThat(writeWithoutIndex.transitionNanos).isPositive()
        assertThat(writeWithIndex.insertNanos).isPositive()
        assertThat(writeWithIndex.transitionNanos).isPositive()

        println(
            "STORE_ORDER_BOARD_EXPLAIN_FIXTURE rows=$FIXTURE_ORDER_COUNT " +
                "active_per_lane=$ACTIVE_ROWS_PER_LANE primary_lane_limit=${StoreOrderBoardPaging.FETCH_LIMIT}",
        )
        println("STORE_ORDER_BOARD_PRIMARY_MIXED_WITHOUT_INDEX\n$primaryWithoutIndex")
        println("STORE_ORDER_BOARD_PRIMARY_MIXED_WITH_INDEX\n$primaryWithIndex")
        println("STORE_ORDER_BOARD_OVERFLOW_COUNT_WITHOUT_INDEX\n$overflowCountWithoutIndex")
        println("STORE_ORDER_BOARD_OVERFLOW_COUNT_WITH_INDEX\n$overflowCountWithIndex")
        println("STORE_ORDER_BOARD_OVERFLOW_PAGE_WITHOUT_INDEX\n$overflowPageWithoutIndex")
        println("STORE_ORDER_BOARD_OVERFLOW_PAGE_WITH_INDEX\n$overflowPageWithIndex")
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
                public_reference varchar(32) NOT NULL,
                pickup_sequence bigint NOT NULL,
                pickup_business_date date NOT NULL,
                state varchar(32) NOT NULL,
                pickup_window_start_snapshot timestamptz NOT NULL,
                pickup_window_end_snapshot timestamptz NOT NULL,
                acceptance_warning_at timestamptz,
                acceptance_deadline_at timestamptz
            )
            """.trimIndent(),
        )
    }

    private fun insertPlanOrders(count: Int) {
        val rows =
            (0 until count).map { sequence ->
                val target = sequence < ACTIVE_ROWS_PER_LANE * StoreOrderBoardLane.entries.size
                val state =
                    if (target) {
                        StoreOrderBoardQuerySql.state(
                            StoreOrderBoardLane.entries[
                                sequence %
                                    StoreOrderBoardLane.entries.size,
                            ],
                        )
                    } else {
                        "COMPLETED"
                    }
                val pickup = START.plusSeconds((sequence / StoreOrderBoardLane.entries.size).toLong())
                arrayOf<Any>(
                    UUID.nameUUIDFromBytes("store-board-plan:$sequence".toByteArray()),
                    if (target) TARGET_STORE_ID else OTHER_STORE_ID,
                    "BF-PLAN-${sequence.toString().padStart(8, '0')}",
                    sequence.toLong() + 1,
                    LocalDate.of(2030, 1, 1),
                    state,
                    Timestamp.from(pickup),
                    Timestamp.from(pickup.plusSeconds(600)),
                    Timestamp.from(pickup.plusSeconds(120)),
                    Timestamp.from(pickup.plusSeconds(180)),
                )
            }
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.ordering_order " +
                "(id, store_id, public_reference, pickup_sequence, pickup_business_date, state, " +
                "pickup_window_start_snapshot, pickup_window_end_snapshot, acceptance_warning_at, acceptance_deadline_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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

    private fun explainPrimaryBoard(): String =
        explain(
            StoreOrderBoardQuerySql.primaryBoard(StoreOrderBoardLane.entries, "$PLAN_SCHEMA.ordering_order"),
            *List(StoreOrderBoardLane.entries.size) { TARGET_STORE_ID }.toTypedArray(),
        )

    private fun explainOverflowCount(): String =
        explain(
            StoreOrderBoardQuerySql.overflowCount(
                statePlaceholders = List(StoreOrderBoardLane.entries.size) { "?" }.joinToString(", "),
                table = "$PLAN_SCHEMA.ordering_order",
            ),
            TARGET_STORE_ID,
            *StoreOrderBoardLane.entries.map(StoreOrderBoardQuerySql::state).toTypedArray(),
        )

    private fun explainOverflowPage(): String =
        explain(
            StoreOrderBoardQuerySql.overflowPage(StoreOrderBoardLane.PENDING_ACCEPTANCE, "$PLAN_SCHEMA.ordering_order"),
            TARGET_STORE_ID,
            StoreOrderBoardQuerySql.state(StoreOrderBoardLane.PENDING_ACCEPTANCE),
            Timestamp.from(START),
            UUID(0, 0),
        )

    private fun explain(
        sql: String,
        vararg arguments: Any,
    ): String =
        jdbcTemplate
            .queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", String::class.java, *arguments)
            .joinToString("\n")

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
                    "BF-WRITE-${sequence.toString().padStart(8, '0')}",
                    sequence.toLong() + 1,
                    LocalDate.of(2030, 1, 1),
                    "PAID",
                    Timestamp.from(START.plusSeconds(sequence.toLong())),
                    Timestamp.from(START.plusSeconds(sequence + 600L)),
                    Timestamp.from(START.plusSeconds(sequence + 120L)),
                    Timestamp.from(START.plusSeconds(sequence + 180L)),
                )
            }
        val insertStart = System.nanoTime()
        jdbcTemplate.batchUpdate(
            "INSERT INTO $PLAN_SCHEMA.$table " +
                "(id, store_id, public_reference, pickup_sequence, pickup_business_date, state, " +
                "pickup_window_start_snapshot, pickup_window_end_snapshot, acceptance_warning_at, acceptance_deadline_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
