@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class SettlementLifecycleMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy {
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
        jdbc.update(
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

    private fun insertStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                it,
            )
        }

    private fun tableCount(name: String): Long =
        requireNotNull(
            jdbc.queryForObject(
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
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Long::class.java,
                tableName,
                columnName,
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
        target?.let(configuration::target)
        return configuration.load()
    }
}
