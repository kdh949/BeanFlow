package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource

/**
 * V33 schema, the V34 fail-closed coverage gate, the startup gate, and the actual GiST query plan.
 *
 * Every case migrates a database created from `template1`, so `CREATE EXTENSION postgis` really has
 * to run and the migration would fail if the role could not create the extension.
 */
@Testcontainers(disabledWithoutDocker = true)
internal class StoreDiscoveryProfileMigrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        const val FIXTURE_STORE_COUNT = 5_000
        const val IN_RADIUS_STORE_COUNT = 50
        const val RADIUS_METERS = 1_000
        const val LIMIT = 101
        const val QUERY_POINT = "ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography"
    }

    @Test
    fun `V33 creates the PostGIS extension, the separate profile table and the GiST index`() {
        val jdbcTemplate = migrated("v33_schema")

        assertThat(count(jdbcTemplate, "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'")).isOne()
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT format_type(atttypid, atttypmod)
                  FROM pg_attribute
                 WHERE attrelid = 'merchant_store_discovery_profile'::regclass AND attname = 'location'
                """.trimIndent(),
                String::class.java,
            ),
        ).isEqualTo("geography(Point,4326)")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = " +
                    "'idx_store_discovery_profile_location'",
                String::class.java,
            ),
        ).contains("USING gist", "location")
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT contype::text FROM pg_constraint
                 WHERE conrelid = 'merchant_store_discovery_profile'::regclass
                """.trimIndent(),
                String::class.java,
            ),
        ).contains("p", "f", "c")
    }

    @Test
    fun `the store write entity does not gain a search name, geometry or spatial index`() {
        val jdbcTemplate = migrated("v33_store_entity_unchanged")

        assertThat(
            jdbcTemplate.queryForList(
                "SELECT attname FROM pg_attribute WHERE attrelid = 'merchant_store'::regclass AND attnum > 0",
                String::class.java,
            ),
        ).containsExactlyInAnyOrder("id", "accepting_orders", "pickup_enabled", "version")
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'merchant_store'",
                String::class.java,
            ),
        ).noneMatch { definition -> definition.orEmpty().contains("gist", ignoreCase = true) }
    }

    @Test
    fun `an empty store inventory passes both the migration gate and the startup gate`() {
        val jdbcTemplate = migrated("v33_empty_inventory")
        val registry = SimpleMeterRegistry()

        assertThatCode { precheck(jdbcTemplate, registry).run(DefaultApplicationArguments()) }.doesNotThrowAnyException()
        assertThat(count(jdbcTemplate, "SELECT count(*) FROM merchant_store")).isZero()
        assertThat(precheckCount(registry, "EMPTY")).isOne()
        assertThat(missingCount(registry)).isZero()
    }

    @Test
    fun `an existing store without a verified profile stops the migration at V34`() {
        val dataSource = database("v33_unresolved_store")
        flyway(dataSource).target("32").load().migrate()
        val jdbcTemplate = JdbcTemplate(dataSource)
        insertStore(jdbcTemplate, UUID.randomUUID())

        assertThatThrownBy { flyway(dataSource).load().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasMessageContaining("without a verified StoreDiscoveryProfile")
        // V33 committed, so the table exists; V34 is the gate that refused. Nothing was backfilled.
        assertThat(appliedVersions(jdbcTemplate)).contains("33").doesNotContain("34")
        assertThat(count(jdbcTemplate, "SELECT count(*) FROM merchant_store_discovery_profile")).isZero()
    }

    @Test
    fun `existing stores can be profiled between V33 and V34 and then migrate cleanly`() {
        val dataSource = database("v33_profile_loading_window")
        flyway(dataSource).target("32").load().migrate()
        val jdbcTemplate = JdbcTemplate(dataSource)
        val storeIds = List(3) { UUID.randomUUID() }
        storeIds.forEach { insertStore(jdbcTemplate, it) }

        // This is the deployment procedure: schema first, then the owner-verified load, then the gate.
        flyway(dataSource).target("33").load().migrate()
        storeIds.forEachIndexed { index, storeId -> insertProfile(jdbcTemplate, storeId, "Verified store $index") }
        assertThatCode { flyway(dataSource).load().migrate() }.doesNotThrowAnyException()

        assertThat(appliedVersions(jdbcTemplate)).contains("33", "34")
        val registry = SimpleMeterRegistry()
        assertThatCode { precheck(jdbcTemplate, registry).run(DefaultApplicationArguments()) }.doesNotThrowAnyException()
        assertThat(precheckCount(registry, "VERIFIED")).isOne()
    }

    @Test
    fun `an empty point is rejected by the table and by the startup gate`() {
        val jdbcTemplate = migrated("v33_empty_point")
        val storeId = UUID.randomUUID()
        insertStore(jdbcTemplate, storeId)

        // POINT EMPTY satisfies geography(Point,4326), GeometryType() and ST_IsValid(), so only an
        // explicit emptiness rule keeps it out.
        assertThatThrownBy { insertEmptyPointProfile(jdbcTemplate, storeId) }
            .hasMessageContaining("merchant_store_discovery_profile")

        jdbcTemplate.execute(
            "ALTER TABLE merchant_store_discovery_profile DROP CONSTRAINT merchant_store_discovery_profile_location_check",
        )
        insertEmptyPointProfile(jdbcTemplate, storeId)

        assertThatThrownBy { precheck(jdbcTemplate, SimpleMeterRegistry()).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 invalid profile(s)")
    }

    private fun insertEmptyPointProfile(
        jdbcTemplate: JdbcTemplate,
        storeId: UUID,
    ) = jdbcTemplate.update(
        """
        INSERT INTO merchant_store_discovery_profile (store_id, name, location)
        VALUES (?, 'Empty point store', ST_GeomFromText('POINT EMPTY', 4326)::geography)
        """.trimIndent(),
        storeId,
    )

    private fun appliedVersions(jdbcTemplate: JdbcTemplate): List<String?> =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL",
            String::class.java,
        )

    @Test
    fun `exact verified coverage passes the startup gate`() {
        val jdbcTemplate = migrated("v33_exact_coverage")
        val registry = SimpleMeterRegistry()
        repeat(3) { index -> insertProfiledStore(jdbcTemplate, UUID.randomUUID(), "Verified store $index") }

        assertThatCode { precheck(jdbcTemplate, registry).run(DefaultApplicationArguments()) }.doesNotThrowAnyException()
        assertThat(precheckCount(registry, "VERIFIED")).isOne()
    }

    @Test
    fun `a store without a profile fails startup and counts the missing profile`() {
        val jdbcTemplate = migrated("v33_missing_profile")
        val registry = SimpleMeterRegistry()
        insertProfiledStore(jdbcTemplate, UUID.randomUUID(), "Verified store")
        insertStore(jdbcTemplate, UUID.randomUUID())

        assertThatThrownBy { precheck(jdbcTemplate, registry).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 store(s) without a profile")
        assertThat(missingCount(registry)).isOne()
        assertThat(precheckCount(registry, "UNRESOLVED")).isOne()
    }

    @Test
    fun `an orphaned profile fails startup even when the foreign key is gone`() {
        val jdbcTemplate = migrated("v33_orphan_profile")
        val registry = SimpleMeterRegistry()
        jdbcTemplate.execute(
            "ALTER TABLE merchant_store_discovery_profile DROP CONSTRAINT merchant_store_discovery_profile_store_id_fkey",
        )
        insertProfile(jdbcTemplate, UUID.randomUUID(), "Orphaned store")

        assertThatThrownBy { precheck(jdbcTemplate, registry).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 orphaned profile(s)")
        assertThat(precheckCount(registry, "UNRESOLVED")).isOne()
    }

    @Test
    fun `a blank name or a non point geometry fails startup even when the schema check is gone`() {
        val blank = migrated("v33_blank_name")
        blank.execute(
            "ALTER TABLE merchant_store_discovery_profile DROP CONSTRAINT merchant_store_discovery_profile_name_check",
        )
        val blankStoreId = UUID.randomUUID()
        insertStore(blank, blankStoreId)
        insertProfile(blank, blankStoreId, "   ")

        assertThatThrownBy { precheck(blank, SimpleMeterRegistry()).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 invalid profile(s)")

        val geometry = migrated("v33_invalid_geometry")
        geometry.execute("ALTER TABLE merchant_store_discovery_profile ALTER COLUMN location TYPE geography")
        val lineStoreId = UUID.randomUUID()
        insertStore(geometry, lineStoreId)
        geometry.update(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location)
            VALUES (?, ?, ST_SetSRID(ST_MakeLine(ST_MakePoint(127.0, 37.5), ST_MakePoint(127.1, 37.6)), 4326)::geography)
            """.trimIndent(),
            lineStoreId,
            "Line store",
        )

        assertThatThrownBy { precheck(geometry, SimpleMeterRegistry()).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("1 invalid profile(s)")
    }

    @Test
    fun `a removed PostGIS extension fails startup instead of degrading to a non spatial search`() {
        val jdbcTemplate = migrated("v33_extension_removed")
        jdbcTemplate.execute("DROP EXTENSION postgis CASCADE")

        assertThatThrownBy { precheck(jdbcTemplate, SimpleMeterRegistry()).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires the PostGIS extension")
    }

    @Test
    fun `the GiST index serves the pickup-capable nearby range query`() {
        val jdbcTemplate = migrated("v33_query_plan")
        seedProfiles(jdbcTemplate)
        jdbcTemplate.execute("ANALYZE merchant_store_discovery_profile")
        jdbcTemplate.execute("ANALYZE merchant_store")

        jdbcTemplate.execute("DROP INDEX idx_store_discovery_profile_location")
        val withoutIndex = explain(jdbcTemplate)
        jdbcTemplate.execute(
            "CREATE INDEX idx_store_discovery_profile_location ON merchant_store_discovery_profile USING GIST (location)",
        )
        jdbcTemplate.execute("ANALYZE merchant_store_discovery_profile")
        val withIndex = explain(jdbcTemplate)

        assertThat(withoutIndex).contains("Seq Scan on merchant_store_discovery_profile")
        assertThat(withIndex).contains("Index Scan using idx_store_discovery_profile_location")
        println("NEARBY_EXPLAIN_FIXTURE stores=$FIXTURE_STORE_COUNT radius=$RADIUS_METERS limit=$LIMIT")
        println("NEARBY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("NEARBY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun seedProfiles(jdbcTemplate: JdbcTemplate) {
        val stores = (0 until FIXTURE_STORE_COUNT).map { UUID.nameUUIDFromBytes("nearby-explain:$it".toByteArray()) }
        jdbcTemplate.batchUpdate(
            "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
            stores.map { arrayOf<Any>(it) },
        )
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO merchant_store_discovery_profile (store_id, name, location)
            VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
            """.trimIndent(),
            stores.mapIndexed { index, storeId ->
                // The first stores form a tight cluster inside the radius; the rest use a
                // one-degree grid that stays far outside it.
                val longitude = if (index < IN_RADIUS_STORE_COUNT) 127.0 + index * 0.0001 else -180.0 + (index % 360)
                val latitude = if (index < IN_RADIUS_STORE_COUNT) 37.5 else -80.0 + (index / 360)
                arrayOf<Any>(storeId, "Fixture store $index", longitude, latitude)
            },
        )
    }

    private fun explain(jdbcTemplate: JdbcTemplate): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT candidate.store_id, candidate.name, candidate.distance_micrometers
                  FROM (
                        SELECT profile.store_id AS store_id,
                               profile.name AS name,
                               floor(ST_Distance(profile.location, $QUERY_POINT) * 1000000)::bigint
                                   AS distance_micrometers
                          FROM merchant_store_discovery_profile profile
                          JOIN merchant_store store ON store.id = profile.store_id
                         WHERE ST_DWithin(profile.location, $QUERY_POINT, $RADIUS_METERS)
                           AND store.accepting_orders
                           AND store.pickup_enabled
                       ) AS candidate
                 ORDER BY candidate.distance_micrometers, candidate.store_id
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
            ).joinToString("\n")

    private fun precheck(
        jdbcTemplate: JdbcTemplate,
        registry: SimpleMeterRegistry,
    ) = StoreDiscoveryProfilePrecheck(jdbcTemplate, registry)

    private fun precheckCount(
        registry: SimpleMeterRegistry,
        outcome: String,
    ): Double =
        registry
            .find("beanflow.discovery.profile.precheck.count")
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    private fun missingCount(registry: SimpleMeterRegistry): Double =
        registry.find("beanflow.discovery.profile.missing.count").counter()?.count() ?: 0.0

    private fun insertProfiledStore(
        jdbcTemplate: JdbcTemplate,
        storeId: UUID,
        name: String,
    ) {
        insertStore(jdbcTemplate, storeId)
        insertProfile(jdbcTemplate, storeId, name)
    }

    private fun insertStore(
        jdbcTemplate: JdbcTemplate,
        storeId: UUID,
    ) = jdbcTemplate.update(
        "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
        storeId,
    )

    private fun insertProfile(
        jdbcTemplate: JdbcTemplate,
        storeId: UUID,
        name: String,
    ) = jdbcTemplate.update(
        """
        INSERT INTO merchant_store_discovery_profile (store_id, name, location)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography)
        """.trimIndent(),
        storeId,
        name,
    )

    private fun count(
        jdbcTemplate: JdbcTemplate,
        sql: String,
    ): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun migrated(name: String): JdbcTemplate =
        database(name).let { dataSource ->
            flyway(dataSource).load().migrate()
            JdbcTemplate(dataSource)
        }

    private fun flyway(dataSource: DataSource) =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")

    /**
     * Creates an isolated database from `template1`, so the PostGIS extension is not inherited from
     * the image-initialised default database and the migration has to create it.
     */
    private fun database(name: String): DataSource {
        JdbcTemplate(dataSource(postgres.databaseName)).execute("""CREATE DATABASE "$name" TEMPLATE template1""")
        return dataSource(name)
    }

    private fun dataSource(databaseName: String): DataSource {
        val withoutQuery = postgres.jdbcUrl.substringBefore('?')
        val query = postgres.jdbcUrl.substringAfter('?', "")
        val url = withoutQuery.substringBeforeLast('/') + "/" + databaseName + if (query.isEmpty()) "" else "?$query"
        return DriverManagerDataSource(url, postgres.username, postgres.password)
    }
}
