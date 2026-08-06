package io.github.kdh949.beanflow.loyalty.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class PointAccountQueryMigrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))

        const val FIXTURE_TRANSACTION_COUNT = 5_000
        const val LIMIT = 101
        val FIXTURE_START: Instant = Instant.parse("2026-08-01T00:00:00Z")
    }

    @Test
    fun `V32 creates the descending account ledger keyset index`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        val indexDefinition =
            JdbcTemplate(dataSource).queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = " +
                    "'idx_point_transaction_account_occurred_id'",
                String::class.java,
            )

        assertThat(indexDefinition)
            .contains("point_account_id", "occurred_at DESC", "id DESC")
    }

    @Test
    fun `fixed ledger fixture records the actual keyset plan before and after the V32 index`() {
        val jdbcTemplate = jdbcTemplate()
        val accountId = UUID.randomUUID()
        val lotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_account (
                id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw, version
            ) VALUES (?, ?, 0, 0, 0, 0)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO loyalty_point_lot (
                id, point_account_id, available_amount_krw, reserved_amount_krw,
                expires_at, issuer_type, issuer_reference, version
            ) VALUES (?, ?, 0, 0, ?, 'PLATFORM', ?, 0)
            """.trimIndent(),
            lotId,
            accountId,
            Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")),
            "point-account-query-explain:$lotId",
        )
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO loyalty_point_transaction (
                id, point_account_id, point_lot_id, amount_krw, type, balance_effect, source_reference, occurred_at
            ) VALUES (?, ?, ?, 1, 'ACCRUAL', 'CREDIT', ?, ?)
            """.trimIndent(),
            (0 until FIXTURE_TRANSACTION_COUNT).map { sequence ->
                arrayOf(
                    UUID.nameUUIDFromBytes("point-account-query-explain:$sequence".toByteArray()),
                    accountId,
                    lotId,
                    "point-account-query-explain:$sequence",
                    Timestamp.from(FIXTURE_START.plusSeconds(sequence.toLong())),
                )
            },
        )
        jdbcTemplate.execute("ANALYZE loyalty_point_transaction")

        jdbcTemplate.execute("DROP INDEX idx_point_transaction_account_occurred_id")
        val withoutIndex = explain(jdbcTemplate, accountId)
        jdbcTemplate.execute(
            "CREATE INDEX idx_point_transaction_account_occurred_id " +
                "ON loyalty_point_transaction (point_account_id, occurred_at DESC, id DESC)",
        )
        jdbcTemplate.execute("ANALYZE loyalty_point_transaction")
        val withIndex = explain(jdbcTemplate, accountId)

        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("Index Scan using idx_point_transaction_account_occurred_id")
        println("POINT_ACCOUNT_QUERY_EXPLAIN_FIXTURE rows=$FIXTURE_TRANSACTION_COUNT limit=$LIMIT")
        println("POINT_ACCOUNT_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("POINT_ACCOUNT_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun jdbcTemplate(): JdbcTemplate {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        return JdbcTemplate(dataSource)
    }

    private fun explain(
        jdbcTemplate: JdbcTemplate,
        accountId: UUID,
    ): String =
        jdbcTemplate.queryForList(
            """
            EXPLAIN (ANALYZE, BUFFERS)
            SELECT id, type, balance_effect, amount_krw, occurred_at, source_reference
              FROM loyalty_point_transaction
             WHERE point_account_id = ?
             ORDER BY occurred_at DESC, id DESC
             LIMIT $LIMIT
            """.trimIndent(),
            String::class.java,
            accountId,
        ).joinToString("\n")

}
