@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Fixed-fixture plan evidence for the V57 favorite and V59 trigram indexes. */
internal class StoreSearchQueryPlanTest : IsolatedPostgresSupport() {
    companion object {
        const val TRIGRAM_FIXTURE_ROW_COUNT = 100_000
        const val FAVORITE_FIXTURE_ROW_COUNT = 20_000
        const val MATCHING_TERM_COUNT = 200
        const val FAVORITE_CUSTOMER_ROW_COUNT = 500
        const val LIMIT = 20
        const val TRIGRAM_PLAN_SCHEMA = "store_search_trigram_query_plan"
        const val FAVORITE_PLAN_SCHEMA = "customer_favorite_store_query_plan"
        val CUSTOMER_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val FIXTURE_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val SIMILARITY_THRESHOLD: BigDecimal = BigDecimal("0.3")
        const val TRIGRAM_VALUE = "harborvew-coffee-roastery"
        const val TRIGRAM_PATTERN = "%harborvew-coffee-roastery%"
    }

    private val dataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @BeforeEach
    fun resetSchema() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `fixed trigram fixture records the V59 predicate plan before and after the GIN index`() {
        assertThat(indexDefinition("ix_search_term_trgm")).contains("gin", "term_normalized gin_trgm_ops")
        insertTrigramFixture()
        jdbc.execute("ANALYZE $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term")

        val withoutIndex = explainTrigramPredicate()
        jdbc.execute(
            "CREATE INDEX ix_search_term_trgm ON $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term " +
                "USING gin (term_normalized gin_trgm_ops)",
        )
        jdbc.execute("ANALYZE $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term")
        val withIndex = explainTrigramPredicate()

        println(
            "STORE_SEARCH_TRIGRAM_QUERY_EXPLAIN_FIXTURE rows=$TRIGRAM_FIXTURE_ROW_COUNT matchingTerms=$MATCHING_TERM_COUNT " +
                "limit=$LIMIT threshold=$SIMILARITY_THRESHOLD",
        )
        println("STORE_SEARCH_TRIGRAM_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("STORE_SEARCH_TRIGRAM_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("ix_search_term_trgm")
    }

    @Test
    fun `fixed favorite fixture records the V57 ordering plan before and after its customer index`() {
        assertThat(indexDefinition("ix_discovery_favorite_customer_created")).contains("customer_id", "created_at DESC", "store_id")
        insertFavoriteFixture()
        jdbc.execute("ANALYZE $FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store")

        val withoutIndex = explainFavoriteQuery()
        jdbc.execute(
            "CREATE INDEX ix_discovery_favorite_customer_created ON " +
                "$FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store (customer_id, created_at DESC, store_id)",
        )
        jdbc.execute("ANALYZE $FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store")
        val withIndex = explainFavoriteQuery()

        assertThat(withoutIndex).contains("Seq Scan")
        assertThat(withIndex).contains("ix_discovery_favorite_customer_created")
        println(
            "CUSTOMER_FAVORITE_STORE_QUERY_EXPLAIN_FIXTURE rows=$FAVORITE_FIXTURE_ROW_COUNT " +
                "customerRows=$FAVORITE_CUSTOMER_ROW_COUNT limit=$LIMIT",
        )
        println("CUSTOMER_FAVORITE_STORE_QUERY_EXPLAIN_WITHOUT_INDEX\n$withoutIndex")
        println("CUSTOMER_FAVORITE_STORE_QUERY_EXPLAIN_WITH_INDEX\n$withIndex")
    }

    private fun insertTrigramFixture() {
        jdbc.execute("DROP SCHEMA IF EXISTS $TRIGRAM_PLAN_SCHEMA CASCADE")
        jdbc.execute("CREATE SCHEMA $TRIGRAM_PLAN_SCHEMA")
        jdbc.execute(
            """
            CREATE TABLE $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term (
                store_id uuid NOT NULL,
                term_kind varchar(32) NOT NULL,
                term_normalized varchar(400) NOT NULL,
                weight numeric(3, 2) NOT NULL
            )
            """.trimIndent(),
        )
        val rows =
            (0 until TRIGRAM_FIXTURE_ROW_COUNT).map { sequence ->
                arrayOf(
                    UUID.nameUUIDFromBytes("search-term-store:$sequence".toByteArray()),
                    "STORE_NAME",
                    if (sequence < MATCHING_TERM_COUNT) {
                        "harborview-coffee-roastery-$sequence"
                    } else {
                        "other-district-catalogue-$sequence"
                    },
                    BigDecimal("1.00"),
                )
            }
        jdbc.batchUpdate(
            """
            INSERT INTO $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term
                (store_id, term_kind, term_normalized, weight)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            rows,
        )
    }

    private fun insertFavoriteFixture() {
        jdbc.execute("DROP SCHEMA IF EXISTS $FAVORITE_PLAN_SCHEMA CASCADE")
        jdbc.execute("CREATE SCHEMA $FAVORITE_PLAN_SCHEMA")
        jdbc.execute(
            """
            CREATE TABLE $FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store (
                customer_id uuid NOT NULL,
                store_id uuid NOT NULL,
                created_at timestamptz NOT NULL
            )
            """.trimIndent(),
        )
        val rows =
            (0 until FAVORITE_FIXTURE_ROW_COUNT).map { sequence ->
                arrayOf(
                    if (sequence < FAVORITE_CUSTOMER_ROW_COUNT) {
                        CUSTOMER_ID
                    } else {
                        UUID.nameUUIDFromBytes("other-customer:${sequence % 100}".toByteArray())
                    },
                    UUID.nameUUIDFromBytes("favorite-store:$sequence".toByteArray()),
                    Timestamp.from(FIXTURE_START.minusSeconds(sequence.toLong())),
                )
            }
        jdbc.batchUpdate(
            """
            INSERT INTO $FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store
                (customer_id, store_id, created_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
            rows,
        )
    }

    private fun explainTrigramPredicate(): String =
        requireNotNull(
            jdbc.execute(
                ConnectionCallback { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.execute("SET LOCAL pg_trgm.similarity_threshold = '$SIMILARITY_THRESHOLD'")
                        }
                        connection
                            .prepareStatement(
                                """
                                EXPLAIN (ANALYZE, BUFFERS)
                                SELECT term.store_id
                                  FROM $TRIGRAM_PLAN_SCHEMA.discovery_store_search_term term
                                 WHERE term.term_normalized LIKE ? ESCAPE '\'
                                    OR (
                                        term.term_normalized % ?
                                        AND similarity(term.term_normalized, ?) >= ?
                                    )
                                 ORDER BY similarity(term.term_normalized, ?) DESC, term.store_id ASC
                                 LIMIT ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, TRIGRAM_PATTERN)
                                statement.setString(2, TRIGRAM_VALUE)
                                statement.setString(3, TRIGRAM_VALUE)
                                statement.setBigDecimal(4, SIMILARITY_THRESHOLD)
                                statement.setString(5, TRIGRAM_VALUE)
                                statement.setInt(6, LIMIT)
                                statement.executeQuery().use { rows ->
                                    buildList {
                                        while (rows.next()) add(rows.getString(1))
                                    }.joinToString("\n")
                                }
                            }
                    } finally {
                        connection.rollback()
                        connection.autoCommit = originalAutoCommit
                    }
                },
            ),
        )

    private fun explainFavoriteQuery(): String =
        jdbc
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT store_id
                  FROM $FAVORITE_PLAN_SCHEMA.discovery_customer_favorite_store
                 WHERE customer_id = ?
                 ORDER BY created_at DESC, store_id ASC
                 LIMIT $LIMIT
                """.trimIndent(),
                String::class.java,
                CUSTOMER_ID,
            ).joinToString("\n")

    private fun indexDefinition(name: String): String =
        requireNotNull(
            jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String::class.java, name),
        ) { "index $name is missing" }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()
}
