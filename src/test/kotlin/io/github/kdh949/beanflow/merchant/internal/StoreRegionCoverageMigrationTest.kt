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
import java.util.UUID

/**
 * The V62 fail-closed region coverage gate (ADR-112 3절).
 *
 * The gate only means something if it actually stops a deployment, so the tests here run the
 * migration against a database that has a store with no region and assert the failure, rather than
 * asserting the column's nullability after a clean run where there was nothing to catch.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class StoreRegionCoverageMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        /** 서울특별시 강남구 역삼동. */
        private const val YEOKSAM = "1168010100"
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateToTheVersionBeforeTheGate() {
        flyway("62", cleanDisabled = false).clean()
        flyway("61").migrate()
    }

    @Test
    fun `the gate stops the deployment when any store has no region`() {
        insertStore("지역 없는 매장", regionCode = null)

        assertThatThrownBy { flyway("62").migrate() }
            .hasMessageContaining("store discovery profile row(s) without a region_code")

        // 실패한 migration은 컬럼을 바꾸지 않았다. 지역이 빈 매장이 남은 채로 NOT NULL이
        // 적용되었다면 그 매장은 이후 어떤 갱신에서도 저장되지 않는다.
        assertThat(nullability("region_code")).isEqualTo("YES")
    }

    @Test
    fun `the gate passes once every store has a region and then holds the column`() {
        val covered = insertStore("지역 있는 매장", regionCode = YEOKSAM)

        flyway("62").migrate()

        assertThat(nullability("region_code")).isEqualTo("NO")
        assertThatThrownBy {
            jdbc.update("UPDATE merchant_store_discovery_profile SET region_code = NULL WHERE store_id = ?", covered)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `an empty deployment passes the gate`() {
        flyway("62").migrate()

        assertThat(nullability("region_code")).isEqualTo("NO")
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(62)
    }

    private fun nullability(column: String): String? =
        jdbc.queryForObject(
            """
            SELECT is_nullable
              FROM information_schema.columns
             WHERE table_name = 'merchant_store_discovery_profile' AND column_name = ?
            """.trimIndent(),
            String::class.java,
            column,
        )

    private fun insertStore(
        name: String,
        regionCode: String?,
    ): UUID {
        val storeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            storeId,
        )
        jdbc.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0361, 37.5006), 4326)::geography, ?)
            """.trimIndent(),
            storeId,
            name,
            regionCode,
        )
        return storeId
    }

    private fun flyway(
        target: String,
        cleanDisabled: Boolean = true,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target(target)
            .cleanDisabled(cleanDisabled)
            .load()
}
