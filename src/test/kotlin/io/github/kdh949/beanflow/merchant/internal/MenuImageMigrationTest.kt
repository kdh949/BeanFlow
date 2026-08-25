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
import java.util.UUID

internal class MenuImageMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V66 adds an all-null or all-present menu image pointer`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_name = 'merchant_menu' AND column_name LIKE 'image_%'
                 ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("image_original_key", "image_thumbnail_key", "image_sha256", "image_updated_at")

        val storeId = UUID.randomUUID()
        val menuId = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
        jdbc.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) VALUES (?, ?, 'Latte', 5000, true, 0)",
            menuId,
            storeId,
        )
        assertThatThrownBy {
            jdbc.update("UPDATE merchant_menu SET image_sha256 = ? WHERE id = ?", HASH, menuId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        jdbc.update(
            """
            UPDATE merchant_menu
               SET image_original_key = 'menus/example/original.jpg',
                   image_thumbnail_key = 'menus/example/thumbnail.jpg',
                   image_sha256 = ?, image_updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            HASH,
            menuId,
        )
        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_menu WHERE id = ?", String::class.java, menuId))
            .isEqualTo(HASH)
    }

    @Test
    fun `V66 is latest and registers menu audit actions`() {
        assertThat(jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java))
            .isEqualTo(66)
        assertThat(
            jdbc.queryForList(
                """
                SELECT action
                  FROM operations_audit_action_category
                 WHERE action IN ('MENU_IMAGE_UPDATED', 'MENU_IMAGE_DELETED')
                 ORDER BY action
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("MENU_IMAGE_DELETED", "MENU_IMAGE_UPDATED")
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
