package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * V35 plan evidence over a multi-store, accumulated catalogue fixture.
 *
 * The test does not force a plan. It captures the normal planner's before/after shape after
 * dropping/recreating precisely the V35 indexes, so a future index or SQL change cannot silently
 * turn the published catalogue bounds back into global scans or a global option join/sort. The
 * menu result may use the ordered covering index or the store index followed by a store-bounded
 * sort. The option result may use an incremental, per-menu sort because that work is bounded by
 * the public `LIMIT 5001`; it must remain an indexed nested-loop plan rather than scanning all
 * options.
 */
internal class StoreCatalogQueryMigrationTest : IsolatedPostgresSupport() {
    companion object {
        const val OTHER_STORE_COUNT = 50
        const val MENUS_PER_STORE = 100
        const val TARGET_MENU_COUNT = 1_000
        const val OPTIONS_PER_MENU = 5
        const val SLOT_HISTORY_AND_FUTURE_COUNT = 1_000
        val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val V35_INDEXES =
            listOf(
                "idx_pickup_slot_store_starts_id",
                "idx_merchant_menu_store_name_id",
                "idx_merchant_menu_store_id",
                "idx_merchant_menu_option_menu_name_id",
            )
        val V35_INDEX_DDL =
            listOf(
                "CREATE INDEX idx_pickup_slot_store_starts_id ON fulfillment_pickup_slot (store_id, starts_at, id) INCLUDE (ends_at, capacity, reserved_count, confirmed_count)",
                "CREATE INDEX idx_merchant_menu_store_name_id ON merchant_menu (store_id, name, id) INCLUDE (base_price_krw, available)",
                "CREATE INDEX idx_merchant_menu_store_id ON merchant_menu (store_id, id)",
                "CREATE INDEX idx_merchant_menu_option_menu_name_id ON merchant_menu_option (menu_id, name, id) INCLUDE (additional_price_krw, available)",
            )
    }

    @Test
    fun `V35 indexes bound multi-store catalogue plans without global scans`() {
        val jdbcTemplate = jdbcTemplate()
        val targetStoreId = UUID.randomUUID()
        seedFixture(jdbcTemplate, targetStoreId)
        analyzeForIndexOnlyPlans(jdbcTemplate)

        assertV35IndexDefinitions(jdbcTemplate)
        V35_INDEXES.forEach { jdbcTemplate.execute("DROP INDEX $it") }
        analyzeForIndexOnlyPlans(jdbcTemplate)
        val before = plans(jdbcTemplate, targetStoreId)

        V35_INDEX_DDL.forEach(jdbcTemplate::execute)
        analyzeForIndexOnlyPlans(jdbcTemplate)
        val after = plans(jdbcTemplate, targetStoreId)

        assertThat(before.pickupSlots).contains("Seq Scan")
        assertThat(before.menus).contains("Seq Scan")
        assertThat(after.pickupSlots).contains("idx_pickup_slot_store_starts_id").doesNotContain("Sort")
        assertThat(after.menus).doesNotContain("Seq Scan")
        assertThat(
            after.menus.contains("idx_merchant_menu_store_name_id") ||
                after.menus.contains("idx_merchant_menu_store_id"),
        ).withFailMessage("The menu plan must stay scoped by one of the V35 store indexes\n%s", after.menus)
            .isTrue()
        assertThat(after.options)
            .contains("idx_merchant_menu_store_id")
            .contains("idx_merchant_menu_option_menu_name_id")
            .contains("Nested Loop")
            .doesNotContain("Seq Scan", "Hash Join")

        println(
            "STORE_CATALOG_QUERY_EXPLAIN_FIXTURE stores=${OTHER_STORE_COUNT + 1} " +
                "menus=${TARGET_MENU_COUNT + OTHER_STORE_COUNT * MENUS_PER_STORE} " +
                "options=${(TARGET_MENU_COUNT + OTHER_STORE_COUNT * MENUS_PER_STORE) * OPTIONS_PER_MENU} " +
                "slots=${(OTHER_STORE_COUNT + 1) * (SLOT_HISTORY_AND_FUTURE_COUNT * 2 + 1)}",
        )
        println("STORE_CATALOG_QUERY_EXPLAIN_PICKUP_BEFORE\n${before.pickupSlots}")
        println("STORE_CATALOG_QUERY_EXPLAIN_PICKUP_AFTER\n${after.pickupSlots}")
        println("STORE_CATALOG_QUERY_EXPLAIN_MENUS_BEFORE\n${before.menus}")
        println("STORE_CATALOG_QUERY_EXPLAIN_MENUS_AFTER\n${after.menus}")
        println("STORE_CATALOG_QUERY_EXPLAIN_OPTIONS_BEFORE\n${before.options}")
        println("STORE_CATALOG_QUERY_EXPLAIN_OPTIONS_AFTER\n${after.options}")
        println("STORE_CATALOG_QUERY_INDEX_BYTES\n${indexSizes(jdbcTemplate)}")
    }

    private fun assertV35IndexDefinitions(jdbcTemplate: JdbcTemplate) {
        assertThat(indexDefinition(jdbcTemplate, "idx_pickup_slot_store_starts_id"))
            .contains("fulfillment_pickup_slot", "store_id", "starts_at", "id")
        assertThat(indexDefinition(jdbcTemplate, "idx_merchant_menu_store_name_id"))
            .contains("merchant_menu", "store_id", "name", "id")
        assertThat(indexDefinition(jdbcTemplate, "idx_merchant_menu_store_id"))
            .contains("merchant_menu", "store_id", "id")
        assertThat(indexDefinition(jdbcTemplate, "idx_merchant_menu_option_menu_name_id"))
            .contains("merchant_menu_option", "menu_id", "name", "id")
    }

    private fun indexDefinition(
        jdbcTemplate: JdbcTemplate,
        indexName: String,
    ): String =
        jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            String::class.java,
            indexName,
        ) ?: error("Missing $indexName")

    private fun seedFixture(
        jdbcTemplate: JdbcTemplate,
        targetStoreId: UUID,
    ) {
        jdbcTemplate.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            targetStoreId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version)
            SELECT gen_random_uuid(), true, true, 0
              FROM generate_series(1, ?) AS i
            """.trimIndent(),
            OTHER_STORE_COUNT,
        )
        jdbcTemplate.update(
            """
            INSERT INTO fulfillment_pickup_slot (
                id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
            )
            SELECT gen_random_uuid(), store.id,
                   ?::timestamptz + slot * interval '5 minutes',
                   ?::timestamptz + slot * interval '5 minutes' + interval '4 minutes',
                   10, 0, 0, 0
              FROM merchant_store store
              CROSS JOIN generate_series(-?, ?) AS slot
            """.trimIndent(),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
            SLOT_HISTORY_AND_FUTURE_COUNT,
            SLOT_HISTORY_AND_FUTURE_COUNT,
        )
        jdbcTemplate.update(
            """
            INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
            SELECT gen_random_uuid(), store.id, 'Menu ' || lpad(menu::text, 6, '0'), 1000, true, 0
              FROM merchant_store store
              CROSS JOIN generate_series(1, $MENUS_PER_STORE) AS menu
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
            SELECT gen_random_uuid(), ?, 'Menu ' || lpad(menu::text, 6, '0'), 1000, true, 0
              FROM generate_series(${MENUS_PER_STORE + 1}, $TARGET_MENU_COUNT) AS menu
            """.trimIndent(),
            targetStoreId,
        )
        jdbcTemplate.execute(
            """
            INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available)
            SELECT gen_random_uuid(), menu.id, 'Option ' || lpad(option::text, 6, '0'), 100, true
              FROM merchant_menu menu
              CROSS JOIN generate_series(1, $OPTIONS_PER_MENU) AS option
            """.trimIndent(),
        )
    }

    private fun plans(
        jdbcTemplate: JdbcTemplate,
        targetStoreId: UUID,
    ): CataloguePlans =
        CataloguePlans(
            pickupSlots =
                explain(
                    jdbcTemplate,
                    """
                    SELECT id, starts_at, ends_at, capacity - reserved_count - confirmed_count AS remaining_capacity
                      FROM fulfillment_pickup_slot
                     WHERE store_id = ? AND starts_at > ? AND starts_at < ?
                     ORDER BY starts_at, id
                     LIMIT 1001
                    """.trimIndent(),
                    targetStoreId,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(7 * 24 * 60 * 60)),
                ),
            menus =
                explain(
                    jdbcTemplate,
                    """
                    SELECT id, name, base_price_krw, available
                      FROM merchant_menu
                     WHERE store_id = ?
                     ORDER BY name, id
                     LIMIT 1001
                    """.trimIndent(),
                    targetStoreId,
                ),
            options =
                explain(
                    jdbcTemplate,
                    """
                    SELECT menu_option.menu_id, menu_option.id, menu_option.name,
                           menu_option.additional_price_krw, menu_option.available
                      FROM (
                          SELECT id
                            FROM merchant_menu
                           WHERE store_id = ?
                           ORDER BY id
                           LIMIT 1001
                      ) menu
                      CROSS JOIN LATERAL (
                          SELECT menu_id, id, name, additional_price_krw, available
                            FROM merchant_menu_option
                           WHERE menu_id = menu.id
                           ORDER BY name, id
                           LIMIT 5001
                      ) menu_option
                     ORDER BY menu.id, menu_option.name, menu_option.id
                     LIMIT 5001
                    """.trimIndent(),
                    targetStoreId,
                ),
        )

    private fun explain(
        jdbcTemplate: JdbcTemplate,
        sql: String,
        vararg arguments: Any,
    ): String =
        jdbcTemplate
            .queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", String::class.java, *arguments)
            .joinToString("\n")

    private fun indexSizes(jdbcTemplate: JdbcTemplate): String =
        jdbcTemplate
            .queryForList(
                """
                SELECT indexrelname || '=' || pg_relation_size(indexrelid)
                  FROM pg_stat_all_indexes
                 WHERE schemaname = 'public' AND indexrelname IN (${V35_INDEXES.joinToString(",") { "'$it'" }})
                 ORDER BY indexrelname
                """.trimIndent(),
                String::class.java,
            ).joinToString("\n")

    private fun analyzeForIndexOnlyPlans(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.execute("VACUUM (ANALYZE) merchant_menu")
        jdbcTemplate.execute("VACUUM (ANALYZE) merchant_menu_option")
        jdbcTemplate.execute("VACUUM (ANALYZE) fulfillment_pickup_slot")
    }

    private fun jdbcTemplate(): JdbcTemplate {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return JdbcTemplate(dataSource)
    }

    private data class CataloguePlans(
        val pickupSlots: String,
        val menus: String,
        val options: String,
    )
}
