package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class CurrentSchemaMetadataContractTest : IsolatedPostgresSupport() {
    private val jdbc by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeAll
    fun migrateCurrentSchema() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .load()
            .migrate()
    }

    @Test
    fun `required PostgreSQL extensions are installed once for the current schema`() {
        assertThat(
            jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('pg_trgm', 'postgis') ORDER BY extname",
                String::class.java,
            ),
        ).containsExactly("pg_trgm", "postgis")
    }

    @Test
    fun `high value aggregate and command tables exist in the current schema`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'identity_customer_account',
                       'identity_merchant_account',
                       'payment_refund_line_allocation',
                       'settlement_batch',
                       'support_action_request',
                       'support_case',
                       'support_compensation_request',
                       'support_profile_change'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "identity_customer_account",
            "identity_merchant_account",
            "payment_refund_line_allocation",
            "settlement_batch",
            "support_action_request",
            "support_case",
            "support_compensation_request",
            "support_profile_change",
        )
    }

    @Test
    fun `high value database constraints are present in one metadata inventory`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT conname
                  FROM pg_constraint
                 WHERE conname IN (
                       'chk_payment_refund_terminal_fields',
                       'chk_audit_retention_class',
                       'chk_audit_retention_provenance',
                       'chk_support_action_request_terminal_result',
                       'chk_support_resolution_actor',
                       'ck_identity_customer_lock_shape',
                       'ck_payment_method_active_default',
                       'fk_audit_action_category',
                       'fk_audit_retention_policy_version',
                       'uq_payment_refund_command_key',
                       'uq_support_case_command_idempotency_scope',
                       'uq_support_compensation_command'
                   )
                 ORDER BY conname
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "chk_audit_retention_class",
            "chk_audit_retention_provenance",
            "chk_payment_refund_terminal_fields",
            "chk_support_action_request_terminal_result",
            "chk_support_resolution_actor",
            "ck_identity_customer_lock_shape",
            "ck_payment_method_active_default",
            "fk_audit_action_category",
            "fk_audit_retention_policy_version",
            "uq_payment_refund_command_key",
            "uq_support_case_command_idempotency_scope",
            "uq_support_compensation_command",
        )
    }

    @Test
    fun `high value lookup and partial indexes are present in one metadata inventory`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND indexname IN (
                       'idx_store_discovery_profile_location',
                       'idx_audit_retention',
                       'ix_ordering_order_customer_recent',
                       'ix_ordering_order_customer_recent_store',
                       'ix_ordering_order_store_acceptance_board',
                       'ix_ordering_order_store_board',
                       'ix_search_term_trgm',
                       'uq_payment_method_customer_active_default',
                       'uq_search_term_identity'
                   )
                 ORDER BY indexname
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "idx_audit_retention",
            "idx_store_discovery_profile_location",
            "ix_ordering_order_customer_recent",
            "ix_ordering_order_customer_recent_store",
            "ix_ordering_order_store_acceptance_board",
            "ix_ordering_order_store_board",
            "ix_search_term_trgm",
            "uq_payment_method_customer_active_default",
            "uq_search_term_identity",
        )
    }
}
