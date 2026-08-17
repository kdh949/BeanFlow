@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class ProtectedSupportProfileQueryPlanTest : IsolatedPostgresSupport() {
    companion object {
        const val FIXTURE_COUNT = 20_000
        const val INDEX_NAME = "idx_identity_customer_support_profile_exact_lookup"
        val BYTEA_LITERAL = Regex("'\\\\x[0-9a-f]+'::bytea")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun prepareFixture() {
        val flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
        flyway.clean()
        flyway.migrate()
        jdbcTemplate.execute(
            """
            INSERT INTO identity_customer_support_profile (
                customer_id, display_name_ciphertext, display_name_key_version, display_name_aad_version,
                masked_display_name, primary_phone_ciphertext, primary_phone_key_version,
                primary_phone_aad_version, masked_primary_phone, created_at, updated_at
            )
            SELECT ('00000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
                   'vault:v7:display-' || i, 7, 1, '고*객',
                   'vault:v7:phone-' || i, 7, 1, '***-****-' || lpad((i % 10000)::text, 4, '0'),
                   TIMESTAMPTZ '2026-08-11 00:00:00Z', TIMESTAMPTZ '2026-08-11 00:00:00Z'
              FROM generate_series(1, $FIXTURE_COUNT) AS i
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO identity_customer_support_profile_exact_index (
                customer_id, criterion_type, index_key_version, blind_index, created_at
            )
            SELECT ('00000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
                   'PHONE', 3, decode(lpad(to_hex(i), 64, '0'), 'hex'),
                   TIMESTAMPTZ '2026-08-11 00:00:00Z'
              FROM generate_series(1, $FIXTURE_COUNT) AS i
            """.trimIndent(),
        )
        jdbcTemplate.execute("ANALYZE identity_customer_support_profile")
        jdbcTemplate.execute("ANALYZE identity_customer_support_profile_exact_index")
    }

    @Test
    fun `representative exact lookup chooses the versioned blind-index B-tree on the same fixture`() {
        val digest = hexDigest(FIXTURE_COUNT - 1)
        jdbcTemplate.execute("DROP INDEX $INDEX_NAME")
        val withoutIndex = explain(digest)
        jdbcTemplate.execute(
            """
            CREATE INDEX $INDEX_NAME
                ON identity_customer_support_profile_exact_index
                    (criterion_type, index_key_version, blind_index, customer_id)
            """.trimIndent(),
        )
        jdbcTemplate.execute("ANALYZE identity_customer_support_profile_exact_index")
        val withIndex = explain(digest)

        println("PROTECTED_PROFILE_EXPLAIN_FIXTURE rows=$FIXTURE_COUNT limit=21")
        println("PROTECTED_PROFILE_EXPLAIN_WITHOUT_INDEX\n${redactDigest(withoutIndex)}")
        println("PROTECTED_PROFILE_EXPLAIN_WITH_INDEX\n${redactDigest(withIndex)}")
        assertThat(withoutIndex).contains("Seq Scan on identity_customer_support_profile_exact_index")
        assertThat(withIndex).contains(INDEX_NAME).doesNotContain("Seq Scan on identity_customer_support_profile_exact_index")
    }

    @Test
    fun `bounded rate window retention candidates use the cleanup index`() {
        jdbcTemplate.execute(
            """
            INSERT INTO support_subject_search_rate_window (
                actor_id, window_started_at, attempt_count, updated_at
            )
            SELECT md5('rate-window-plan-' || item)::uuid,
                   date_bin(
                       INTERVAL '5 minutes',
                       clock_timestamp() - INTERVAL '25 hours',
                       TIMESTAMPTZ '1970-01-01 00:00:00Z'
                   ),
                   1,
                   date_bin(
                       INTERVAL '5 minutes',
                       clock_timestamp() - INTERVAL '25 hours',
                       TIMESTAMPTZ '1970-01-01 00:00:00Z'
                   ) + INTERVAL '1 minute'
              FROM generate_series(1, $FIXTURE_COUNT) AS item
            """.trimIndent(),
        )
        jdbcTemplate.execute("ANALYZE support_subject_search_rate_window")

        val plan =
            jdbcTemplate
                .queryForList(
                    """
                    EXPLAIN (ANALYZE, BUFFERS)
                    SELECT actor_id, window_started_at
                      FROM support_subject_search_rate_window
                     WHERE window_started_at < clock_timestamp() - INTERVAL '24 hours'
                     ORDER BY window_started_at, actor_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 100
                    """.trimIndent(),
                    String::class.java,
                ).joinToString("\n")

        println("SUPPORT_RATE_RETENTION_EXPLAIN_FIXTURE rows=$FIXTURE_COUNT limit=100\n$plan")
        assertThat(plan).contains("idx_support_subject_search_rate_window_cleanup")
    }

    private fun explain(digest: ByteArray): String =
        jdbcTemplate
            .queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT profile.customer_id, profile.masked_display_name,
                       profile.masked_primary_phone, profile.masked_primary_email
                  FROM identity_customer_support_profile_exact_index exact_index
                  JOIN identity_customer_support_profile profile ON profile.customer_id = exact_index.customer_id
                 WHERE exact_index.criterion_type = 'PHONE'
                   AND exact_index.index_key_version = 3
                   AND exact_index.blind_index = ?
                 ORDER BY exact_index.customer_id
                 LIMIT 21
                """.trimIndent(),
                String::class.java,
                digest,
            ).joinToString("\n")

    private fun hexDigest(value: Int): ByteArray =
        jdbcTemplate.queryForObject(
            "SELECT decode(lpad(to_hex(?), 64, '0'), 'hex')",
            ByteArray::class.java,
            value,
        )!!

    private fun redactDigest(plan: String): String = plan.replace(BYTEA_LITERAL, "'<redacted>'::bytea")
}
