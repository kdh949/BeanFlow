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

internal class StoreCustomerDisplayMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V67 creates optional Store display profile without inventing existing hours`() {
        val existingStore = seedStore()

        assertThat(jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java))
            .isEqualTo(67)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_customer_display_profile", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_store_operating_hours", Long::class.java)).isZero()

        jdbc.update(
            """
            INSERT INTO merchant_store_customer_display_profile
                (store_id, address_line, directions_hint, version, created_at, updated_at)
            VALUES (?, '서울시 성동구 연무장길 1', '성수역 3번 출구에서 5분', 0, now(), now())
            """.trimIndent(),
            existingStore,
        )
        (1..7).forEach { day ->
            if (day == 7) {
                jdbc.update(
                    "INSERT INTO merchant_store_operating_hours (store_id, day_of_week, closed, opens_at, closes_at) " +
                        "VALUES (?, ?, true, NULL, NULL)",
                    existingStore,
                    day,
                )
            } else {
                jdbc.update(
                    "INSERT INTO merchant_store_operating_hours (store_id, day_of_week, closed, opens_at, closes_at) " +
                        "VALUES (?, ?, false, TIME '09:00', TIME '18:00')",
                    existingStore,
                    day,
                )
            }
        }

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM merchant_store_operating_hours WHERE store_id = ?",
                Long::class.java,
                existingStore,
            ),
        ).isEqualTo(7)
    }

    @Test
    fun `V67 rejects untrimmed control and invalid operating hour tuples`() {
        val storeId = seedStore()

        listOf(" address", "address ", "line\nfeed").forEach { invalid ->
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO merchant_store_customer_display_profile
                        (store_id, address_line, version, created_at, updated_at)
                    VALUES (?, ?, 0, now(), now())
                    """.trimIndent(),
                    storeId,
                    invalid,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        jdbc.update(
            "INSERT INTO merchant_store_customer_display_profile " +
                "(store_id, version, created_at, updated_at) VALUES (?, 0, now(), now())",
            storeId,
        )
        listOf(
            "VALUES (?, 0, true, NULL, NULL)",
            "VALUES (?, 1, true, TIME '09:00', NULL)",
            "VALUES (?, 2, false, NULL, TIME '18:00')",
            "VALUES (?, 3, false, TIME '18:00', TIME '09:00')",
            "VALUES (?, 4, false, TIME '09:00', TIME '09:00')",
        ).forEach { values ->
            assertThatThrownBy {
                jdbc.update(
                    "INSERT INTO merchant_store_operating_hours " +
                        "(store_id, day_of_week, closed, opens_at, closes_at) $values",
                    storeId,
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
    }

    @Test
    fun `V67 adds constrained optional Menu display metadata and audit actions`() {
        val storeId = seedStore()
        val menuId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) " +
                "VALUES (?, ?, 'Latte', 5000, true, 0)",
            menuId,
            storeId,
        )

        jdbc.update(
            "UPDATE merchant_menu SET display_category = '커피', public_description = '고소한 카페라떼' WHERE id = ?",
            menuId,
        )
        assertThat(
            jdbc.queryForMap("SELECT display_category, public_description FROM merchant_menu WHERE id = ?", menuId),
        ).containsEntry("display_category", "커피").containsEntry("public_description", "고소한 카페라떼")

        listOf(" category", "category ", "bad\ncategory").forEach { invalid ->
            assertThatThrownBy {
                jdbc.update("UPDATE merchant_menu SET display_category = ? WHERE id = ?", invalid, menuId)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
        assertThat(
            jdbc.queryForList(
                """
                SELECT action FROM operations_audit_action_category
                 WHERE action IN ('STORE_CUSTOMER_DISPLAY_UPDATED', 'MENU_DISPLAY_CONTENT_UPDATED')
                 ORDER BY action
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("MENU_DISPLAY_CONTENT_UPDATED", "STORE_CUSTOMER_DISPLAY_UPDATED")
    }

    private fun seedStore(): UUID =
        UUID.randomUUID().also { storeId ->
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
        }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
