@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class CustomerInquiryQueryPlanTest : IsolatedPostgresSupport() {
    @Test
    fun `additive migration indexes mixed global inquiry pages after V82`() {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        config.target("82").load().migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        jdbc.execute(
            """
            INSERT INTO support_case
                (id, requester_type, requester_reference, category, priority, reason, state,
                 current_assignee_id, opened_at, last_changed_at, retention_policy_version_id)
            SELECT md5('inquiry-case:' || n)::uuid, 'CUSTOMER', 'query-fixture', 'OTHER', 'NORMAL', '상품 문의', 'WAITING',
                   md5('query-agent')::uuid, timestamptz '2026-09-10 00:00:00Z', timestamptz '2026-09-10 00:00:00Z',
                   (SELECT max(policy_version_id) FROM operations_retention_policy_version WHERE category = 'SUPPORT_CASE')
              FROM generate_series(1, 10000) n
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO support_customer_inquiry
                (id, customer_id, title, category, support_case_id, created_at, updated_at, retention_policy_version_id)
            SELECT md5('query-inquiry:' || n)::uuid, md5('query-customer:' || n)::uuid, '상품 문의', 'OTHER',
                   CASE WHEN n % 2 = 0 THEN md5('inquiry-case:' || (n / 2))::uuid ELSE NULL END,
                   timestamptz '2026-09-10 00:00:00Z' + n * interval '1 second',
                   timestamptz '2026-09-10 00:00:00Z' + n * interval '1 second',
                   (SELECT max(policy_version_id) FROM operations_retention_policy_version WHERE category = 'SUPPORT_CASE')
              FROM generate_series(1, 20000) n
            """.trimIndent(),
        )
        jdbc.execute("ANALYZE support_customer_inquiry")
        val queries =
            listOf(
                "SELECT * FROM support_customer_inquiry ORDER BY created_at DESC, id DESC LIMIT 21",
                "SELECT * FROM support_customer_inquiry WHERE (created_at, id) < " +
                    "(timestamptz '2026-09-10 04:00:00Z', 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) " +
                    "ORDER BY created_at DESC, id DESC LIMIT 21",
            )

        fun plans() =
            queries.map { sql ->
                jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", String::class.java).joinToString("\n")
            }
        val before = plans()
        val result =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()
        assertThat(result.migrations.map { it.version }).contains("82.1")
        jdbc.execute("ANALYZE support_customer_inquiry")
        val after = plans()
        before.forEach { assertThat(it).contains("Seq Scan on support_customer_inquiry", "Sort") }
        after.forEach { assertThat(it).contains("Index Scan using idx_support_inquiry_global_page").doesNotContain("Seq Scan", "Sort") }
        println(
            "INQUIRY_GLOBAL_PLAN rows=20000 claimed=10000 limit=21\nBEFORE\n${before.joinToString(
                "\n",
            )}\nAFTER\n${after.joinToString("\n")}",
        )
    }
}
