package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

internal class MenuCatalogMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun cleanDatabase() {
        flyway(cleanDisabled = false).clean()
    }

    @Test
    fun `V70 backfills existing catalogue rows as active at trade version zero`() {
        flyway(target = "69").migrate()
        val storeId = UUID.randomUUID()
        val menuId = UUID.randomUUID()
        val optionId = UUID.randomUUID()
        val configurationId = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", storeId)
        jdbc.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) VALUES (?, ?, '라테', 4500, true)",
            menuId,
            storeId,
        )
        jdbc.update(
            "INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available) VALUES (?, ?, '샷', 500, true)",
            optionId,
            menuId,
        )
        jdbc.update(
            "INSERT INTO merchant_menu_configuration (id, menu_id, normalized_option_key, available) VALUES (?, ?, ?, true)",
            configurationId,
            menuId,
            optionId.toString(),
        )

        flyway().migrate()

        val menu =
            jdbc.queryForMap(
                "SELECT lifecycle, trade_version, trade_updated_at, archived_at FROM merchant_menu WHERE id = ?",
                menuId,
            )
        assertThat(menu["lifecycle"]).isEqualTo("ACTIVE")
        assertThat(menu["trade_version"]).isEqualTo(0L)
        assertThat((menu["trade_updated_at"] as java.sql.Timestamp).toInstant()).isEqualTo(Instant.EPOCH)
        assertThat(menu["archived_at"]).isNull()
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM merchant_menu_option WHERE id = ?", String::class.java, optionId))
            .isEqualTo("ACTIVE")
        assertThat(
            jdbc.queryForObject("SELECT lifecycle FROM merchant_menu_configuration WHERE id = ?", String::class.java, configurationId),
        ).isEqualTo("ACTIVE")
    }

    @Test
    fun `V70 enforces lifecycle tuples active configuration uniqueness and replay retention`() {
        flyway().migrate()
        val storeId = UUID.randomUUID()
        val menuId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", storeId)
        jdbc.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) VALUES (?, ?, '라테', 4500, false)",
            menuId,
            storeId,
        )
        jdbc.update(
            "INSERT INTO merchant_menu_catalog_command (id, actor_id, operation, idempotency_key, payload_hash, store_id, menu_id, response_json, created_at, retention_expires_at) VALUES (?, ?, 'CREATE_MENU_V1', 'menu-create-key-001', ?, ?, ?, '{}', ?, ?)",
            UUID.randomUUID(),
            actorId,
            "a".repeat(64),
            storeId,
            menuId,
            java.sql.Timestamp.from(Instant.parse("2026-08-27T00:00:00Z")),
            java.sql.Timestamp.from(Instant.parse("2026-11-25T00:00:00Z")),
        )

        assertThatThrownBy {
            jdbc.update("UPDATE merchant_menu SET lifecycle = 'ARCHIVED' WHERE id = ?", menuId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO merchant_menu_catalog_command (id, actor_id, operation, idempotency_key, payload_hash, store_id, menu_id, response_json, created_at, retention_expires_at) VALUES (?, ?, 'ARCHIVE_MENU_V1', 'menu-create-key-001', ?, ?, ?, '{}', now(), now() + interval '90 days')",
                UUID.randomUUID(),
                actorId,
                "b".repeat(64),
                storeId,
                menuId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbc.queryForList(
                "SELECT action FROM operations_audit_action_category WHERE action LIKE 'MENU_CATALOG_%' ORDER BY action",
                String::class.java,
            ),
        ).containsExactly("MENU_CATALOG_ARCHIVED", "MENU_CATALOG_CREATED", "MENU_CATALOG_UPDATED")
    }

    private fun flyway(
        cleanDisabled: Boolean = true,
        target: String? = null,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .cleanDisabled(cleanDisabled)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}
