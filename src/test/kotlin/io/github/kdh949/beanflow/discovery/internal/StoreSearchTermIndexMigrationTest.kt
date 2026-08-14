package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

/**
 * The V59 search index schema.
 *
 * The migration deliberately inserts nothing: filling the index in SQL would mean writing the
 * normalizer a second time, and `lower()` cannot reproduce the Kotlin one (MD-2026-018). The
 * initial load belongs to `StoreSearchIndexRebuildService`, covered by
 * `StoreSearchIndexRebuildIntegrationTest`.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class StoreSearchTermIndexMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        private val STORE_NAME_WEIGHT = BigDecimal("1.00")
        private val MENU_NAME_WEIGHT = BigDecimal("0.70")
        private val REGION_WEIGHT = BigDecimal("0.80")
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V59 creates the term table with its identity, trigram and store-kind indexes`() {
        assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'discovery_store_search_term' ORDER BY indexname",
                String::class.java,
            ),
        ).containsExactly(
            "discovery_store_search_term_pkey",
            "ix_search_term_store_kind",
            "ix_search_term_trgm",
            "uq_search_term_identity",
        )
        // 후보 탐색이 유사도로 떨어질 때 순차 스캔이 되지 않으려면 trigram 연산자 클래스여야 한다.
        assertThat(indexDefinition("ix_search_term_trgm")).contains("gin", "term_normalized gin_trgm_ops")
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(59)
    }

    @Test
    fun `the index starts empty because SQL cannot produce the normalized text`() {
        insertStoreWithProfile("스타벅스 강남점")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isZero()
        // migration이 SQL로 정규화를 흉내 냈다면 여기 행이 생기고, 그 행은 질의가 만드는 문자열과
        // 다를 수 있다. 초기 적재는 재색인이 Kotlin 정규화 함수로 수행한다(MD-2026-018).
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname LIKE '%normalize_search_text%'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `term identity is unique per store, kind, source and text`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT)

        assertThatThrownBy { insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        // 같은 이름의 서로 다른 메뉴 두 개는 출처가 달라 함께 존재할 수 있다.
        val firstMenu = insertMenu(storeId, "아메리카노", available = true)
        val secondMenu = insertMenu(storeId, "아메리카노", available = true)
        insertTerm(storeId, "MENU_NAME", firstMenu, "아메리카노", MENU_NAME_WEIGHT)
        insertTerm(storeId, "MENU_NAME", secondMenu, "아메리카노", MENU_NAME_WEIGHT)
        assertThat(countTerms(storeId, "MENU_NAME")).isEqualTo(2)
    }

    @Test
    fun `only menu terms carry a source and weights stay inside the ranking range`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)

        assertThatThrownBy { insertTerm(storeId, "STORE_NAME", menuId, "다른 이름", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "MENU_NAME", null, "아메리카노", MENU_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "REGION_RI", null, "   ", REGION_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "REGION_RI", null, "감정리", BigDecimal("0.00")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertTerm(storeId, "SLOGAN", null, "무언가", STORE_NAME_WEIGHT) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `every ADR-103 term kind is accepted including the ri level`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)

        listOf("STORE_NAME", "BRAND_NAME", "REGION_SIDO", "REGION_SIGUNGU", "REGION_EUPMYEONDONG", "REGION_RI")
            .forEach { kind -> insertTerm(storeId, kind, null, "값 $kind", REGION_WEIGHT) }
        insertTerm(storeId, "MENU_NAME", menuId, "아메리카노", MENU_NAME_WEIGHT)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isEqualTo(7)
    }

    @Test
    fun `deleting a store removes the terms that pointed at it`() {
        val storeId = insertStoreWithProfile("스타벅스 강남점")
        val menuId = insertMenu(storeId, "아메리카노", available = true)
        insertTerm(storeId, "STORE_NAME", null, "스타벅스 강남점", STORE_NAME_WEIGHT)
        insertTerm(storeId, "MENU_NAME", menuId, "아메리카노", MENU_NAME_WEIGHT)

        jdbc.update("DELETE FROM merchant_menu WHERE store_id = ?", storeId)
        jdbc.update("DELETE FROM merchant_store_discovery_profile WHERE store_id = ?", storeId)
        jdbc.update("DELETE FROM merchant_store WHERE id = ?", storeId)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isZero()
    }

    private fun indexDefinition(name: String): String =
        checkNotNull(
            jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String::class.java, name),
        ) { "index $name is missing" }

    private fun countTerms(
        storeId: UUID,
        kind: String,
    ): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_store_search_term WHERE store_id = ? AND term_kind = ?",
            Long::class.java,
            storeId,
            kind,
        ) ?: 0

    private fun insertTerm(
        storeId: UUID,
        kind: String,
        sourceId: UUID?,
        termNormalized: String,
        weight: BigDecimal,
    ) {
        jdbc.update(
            """
            INSERT INTO discovery_store_search_term
                (id, store_id, term_kind, source_id, term_normalized, display_text, weight)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            storeId,
            kind,
            sourceId,
            termNormalized,
            termNormalized,
            weight,
        )
    }

    private fun insertStoreWithProfile(name: String): UUID {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
            """.trimIndent(),
            storeId,
            name,
            127.0361,
            37.5006,
        )
        return storeId
    }

    private fun insertMenu(
        storeId: UUID,
        name: String,
        available: Boolean,
    ): UUID {
        val menuId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version)
            VALUES (?, ?, ?, 4500, ?, 0)
            """.trimIndent(),
            menuId,
            storeId,
            name,
            available,
        )
        return menuId
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("59")
            .cleanDisabled(cleanDisabled)
            .load()
}
