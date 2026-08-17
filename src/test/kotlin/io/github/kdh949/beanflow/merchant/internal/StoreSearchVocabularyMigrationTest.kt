package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class StoreSearchVocabularyMigrationTest : IsolatedPostgresSupport() {
    companion object {
        /** `scripts/generate-region-seed.py`가 2026-07-08 배포본에서 만든 행 수. */
        private const val SEEDED_REGION_COUNT = 20560L

        /** 그중 리 단위 행. 전체의 74%라 리를 빠뜨리면 지역 검색의 대부분이 비게 된다. */
        private const val SEEDED_RI_COUNT = 15209L
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeAll
    fun migrateFromCleanDatabaseOnce() {
        flyway().migrate()
    }

    @AfterEach
    fun clearTestRows() {
        jdbc.update("DELETE FROM discovery_customer_favorite_store")
        jdbc.update("DELETE FROM merchant_store_discovery_profile")
        jdbc.update("DELETE FROM merchant_store")
        jdbc.update("DELETE FROM merchant_brand")
        jdbc.update("DELETE FROM identity_customer_account WHERE login_id LIKE 'search-%'")
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
                "SELECT sido, sigungu, eupmyeondong, ri, full_name FROM merchant_region WHERE code = ?",
                "1168010100",
            ),
        ).singleElement()
            .isEqualTo(
                mapOf(
                    "sido" to "서울특별시",
                    "sigungu" to "강남구",
                    "eupmyeondong" to "역삼동",
                    "ri" to "",
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
    fun `ri level regions keep both the parent eupmyeondong and their own ri name`() {
        // 리 이름을 eupmyeondong에 덮어썼다면 이 매장은 "동면"으로 검색되지 않는다.
        // 두 열이 모두 채워져야 읍·면 이름과 리 이름 양쪽으로 찾힌다(ADR-112 리 Amendment).
        val gamjeong =
            jdbc.queryForMap(
                "SELECT sido, sigungu, eupmyeondong, ri, full_name FROM merchant_region WHERE code = ?",
                "5111031024",
            )
        assertThat(gamjeong["sido"]).isEqualTo("강원특별자치도")
        assertThat(gamjeong["sigungu"]).isEqualTo("춘천시")
        assertThat(gamjeong["eupmyeondong"]).isEqualTo("동면")
        assertThat(gamjeong["ri"]).isEqualTo("감정리")
        assertThat(gamjeong["full_name"]).isEqualTo("강원특별자치도 춘천시 동면 감정리")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region WHERE ri <> ''", Long::class.java))
            .isEqualTo(SEEDED_RI_COUNT)
    }

    @Test
    fun `regions without a ri level store an empty string rather than null`() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region WHERE ri IS NULL", Long::class.java)).isZero()
        assertThat(
            jdbc.queryForObject("SELECT ri FROM merchant_region WHERE code = ?", String::class.java, "1168010100"),
        ).isEmpty()
        // 리가 없는 행은 코드 뒤 2자리가 00이고, 그 역도 성립해야 계층 판별이 어긋나지 않는다.
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM merchant_region WHERE (ri <> '') <> (right(code, 2) <> '00')",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `duplicated ri names across the country stay distinct rows`() {
        // "상리"는 전국에 여러 개다. 반경 필터가 거르므로 별도 식별자를 두지 않는다(ADR-112 R3).
        // 여기서는 어휘가 이름 중복을 이유로 행을 잃지 않는 것만 확인한다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_region WHERE ri = ?", Long::class.java, "상리"))
            .isGreaterThan(1)
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
        val seed =
            checkNotNull(javaClass.getResourceAsStream("/db/migration/V58__seed_merchant_region.sql")) {
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
        val customerId = insertCustomer()
        val storeId = insertStoreWithProfile()
        insertFavorite(customerId, storeId)
        assertThatThrownBy { insertFavorite(customerId, storeId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        insertFavorite(customerId, insertStoreWithProfile())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_customer_favorite_store WHERE customer_id = ?",
                Long::class.java,
                customerId,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `favorite rejects unknown customer or store references`() {
        val customerId = insertCustomer()
        val storeId = insertStoreWithProfile()

        assertThatThrownBy { insertFavorite(UUID.randomUUID(), storeId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertFavorite(customerId, UUID.randomUUID()) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `favorite rows are deleted with either customer or store`() {
        val customerId = insertCustomer()
        val storeId = insertStoreWithProfile()
        insertFavorite(customerId, storeId)

        jdbc.update("DELETE FROM identity_customer_account WHERE id = ?", customerId)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_customer_favorite_store", Long::class.java)).isZero()

        val secondCustomerId = insertCustomer()
        val secondStoreId = insertStoreWithProfile()
        insertFavorite(secondCustomerId, secondStoreId)
        jdbc.update("DELETE FROM merchant_store_discovery_profile WHERE store_id = ?", secondStoreId)
        jdbc.update("DELETE FROM merchant_store WHERE id = ?", secondStoreId)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_customer_favorite_store", Long::class.java)).isZero()
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

    private fun insertCustomer(): UUID {
        val customerId = UUID.randomUUID()
        val now = Timestamp.from(Instant.parse("2026-08-15T00:00:00Z"))
        jdbc.update(
            """
            INSERT INTO identity_customer_account
                (id, login_id, password_hash, credential_version, display_name, state, locked_until, created_at, updated_at, version)
            VALUES (?, ?, ?, 0, '검색 테스트 고객', 'ACTIVE', NULL, ?, ?, 0)
            """.trimIndent(),
            customerId,
            "search-${customerId.toString().replace("-", "").take(24)}",
            "test-password-hash",
            now,
            now,
        )
        return customerId
    }

    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("58")
            .load()
}
