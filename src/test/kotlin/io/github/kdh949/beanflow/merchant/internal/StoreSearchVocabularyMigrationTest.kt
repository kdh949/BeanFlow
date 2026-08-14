package io.github.kdh949.beanflow.merchant.internal

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class StoreSearchVocabularyMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        /** `scripts/generate-region-seed.py`가 2026-07-08 배포본에서 만든 행 수. */
        private const val SEEDED_REGION_COUNT = 20560L
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(false).clean()
        flyway().migrate()
    }

    @Test
    fun `V57 creates the search vocabulary schema and enables pg_trgm`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('merchant_region', 'merchant_brand', 'discovery_customer_favorite_store')
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("discovery_customer_favorite_store", "merchant_brand", "merchant_region")
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Long::class.java),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(58)
    }

    @Test
    fun `brand and region columns start empty and nullable so existing stores keep migrating`() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_brand", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_customer_favorite_store", Long::class.java)).isZero()
        assertThat(nullable("merchant_store", "brand_id")).isEqualTo("YES")
        // 단계 3 커버리지 gate 전까지는 nullable이어야 기존 매장이 있는 환경에서 값을 채울 창이 생긴다.
        assertThat(nullable("merchant_store_discovery_profile", "region_code")).isEqualTo("YES")
    }

    @Test
    fun `V58 seeds active legal-dong codes including the representative Yeoksam-dong row`() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region", Long::class.java))
            .isEqualTo(SEEDED_REGION_COUNT)
        assertThat(
            jdbc.queryForList(
                "SELECT sido, sigungu, eupmyeondong, full_name FROM merchant_region WHERE code = ?",
                "1168010100",
            ),
        ).singleElement()
            .isEqualTo(
                mapOf(
                    "sido" to "서울특별시",
                    "sigungu" to "강남구",
                    "eupmyeondong" to "역삼동",
                    "full_name" to "서울특별시 강남구 역삼동",
                ),
            )
    }

    @Test
    fun `regions without a sigungu level are stored as an empty string rather than null`() {
        val sejong = jdbc.queryForMap("SELECT sido, sigungu, eupmyeondong FROM merchant_region WHERE code = ?", "3611010100")
        assertThat(sejong["sido"]).isEqualTo("세종특별자치시")
        assertThat(sejong["sigungu"]).isEqualTo("")
        assertThat(sejong["eupmyeondong"]).isEqualTo("반곡동")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region WHERE sigungu IS NULL", Long::class.java)).isZero()
    }

    @Test
    fun `multi-word sigungu keeps the full district instead of splitting on whitespace`() {
        // "경기도 부천시 원미구"는 시군구가 두 단어다. 공백 개수로 잘랐다면 "부천시"만 남는다.
        val bucheon = jdbc.queryForMap("SELECT sido, sigungu, eupmyeondong FROM merchant_region WHERE code = ?", "4119210100")
        assertThat(bucheon["sido"]).isEqualTo("경기도")
        assertThat(bucheon["sigungu"]).isEqualTo("부천시 원미구")
        assertThat(bucheon["eupmyeondong"]).isEqualTo("원미동")
    }

    @Test
    fun `re-running the region seed keeps the row count identical`() {
        val seed = checkNotNull(javaClass.getResourceAsStream("/db/migration/V58__seed_merchant_region.sql")) {
            "region seed migration is missing from the classpath"
        }.reader(Charsets.UTF_8).readText()

        jdbc.execute(seed)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region", Long::class.java))
            .isEqualTo(SEEDED_REGION_COUNT)
    }

    @Test
    fun `only one active brand may hold a normalized name while archived names are reusable`() {
        insertBrand("스타벅스", "스타벅스", "ACTIVE")
        assertThatThrownBy { insertBrand("Starbucks", "스타벅스", "ACTIVE") }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        insertBrand("스타벅스", "스타벅스", "ARCHIVED")
        insertBrand("스타벅스", "스타벅스", "ARCHIVED")

        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM merchant_brand WHERE normalized_name = ?", Long::class.java, "스타벅스"),
        ).isEqualTo(3)
    }

    @Test
    fun `brand rejects blank names and unknown status values`() {
        assertThatThrownBy { insertBrand("   ", "blank", "ACTIVE") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertBrand("이름", "   ", "ACTIVE") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertBrand("이름", "이름", "DELETED") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `store region and brand references only accept existing rows`() {
        val storeId = insertStoreWithProfile()

        assertThatThrownBy { setRegionCode(storeId, "9999999999") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update("UPDATE merchant_store SET brand_id = ? WHERE id = ?", UUID.randomUUID(), storeId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        setRegionCode(storeId, "1168010100")
        assertThat(
            jdbc.queryForObject(
                "SELECT region_code FROM merchant_store_discovery_profile WHERE store_id = ?",
                String::class.java,
                storeId,
            ),
        ).isEqualTo("1168010100")
    }

    @Test
    fun `favorite store rows are unique per customer and store`() {
        val customerId = UUID.randomUUID()
        val storeId = UUID.randomUUID()
        insertFavorite(customerId, storeId)
        assertThatThrownBy { insertFavorite(customerId, storeId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        insertFavorite(customerId, UUID.randomUUID())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_customer_favorite_store WHERE customer_id = ?",
                Long::class.java,
                customerId,
            ),
        ).isEqualTo(2)
    }

    /** 매장과 검색 profile을 한 쌍으로 만든다. V34 커버리지 gate가 짝 없는 매장을 막는다. */
    private fun insertStoreWithProfile(): UUID {
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
            "테스트 매장",
            127.0361,
            37.5006,
        )
        return storeId
    }

    private fun setRegionCode(
        storeId: UUID,
        regionCode: String,
    ) {
        jdbc.update("UPDATE merchant_store_discovery_profile SET region_code = ? WHERE store_id = ?", regionCode, storeId)
    }

    private fun nullable(
        table: String,
        column: String,
    ): String? =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
            String::class.java,
            table,
            column,
        )

    private fun insertBrand(
        name: String,
        normalizedName: String,
        status: String,
    ) {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        jdbc.update(
            """
            INSERT INTO merchant_brand (id, name, normalized_name, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            name,
            normalizedName,
            status,
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun insertFavorite(
        customerId: UUID,
        storeId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO discovery_customer_favorite_store (customer_id, store_id, created_at) VALUES (?, ?, ?)",
            customerId,
            storeId,
            Timestamp.from(Instant.parse("2026-08-15T00:00:00Z")),
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("58")
            .cleanDisabled(cleanDisabled)
            .load()
}
