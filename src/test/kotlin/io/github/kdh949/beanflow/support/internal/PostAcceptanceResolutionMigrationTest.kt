@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class PostAcceptanceResolutionMigrationTest : IsolatedPostgresSupport() {
    companion object {
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun migrate() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V46 creates exact resolution and owner step schema without raw evidence`() {
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_post_acceptance_resolution',
                       'support_post_acceptance_resolution_command',
                       'support_post_acceptance_resolution_step',
                       'loyalty_support_resolution_point_restoration',
                       'promotion_support_resolution_coupon_restoration',
                       'settlement_support_resolution_adjustment'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "loyalty_support_resolution_point_restoration",
            "promotion_support_resolution_coupon_restoration",
            "settlement_support_resolution_adjustment",
            "support_post_acceptance_resolution",
            "support_post_acceptance_resolution_command",
            "support_post_acceptance_resolution_step",
        )
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_post_acceptance_resolution',
                       'support_post_acceptance_resolution_command',
                       'support_post_acceptance_resolution_step'
                   )
                   AND column_name IN (
                       'raw_payload', 'payload_json', 'reason', 'reason_detail',
                       'raw_evidence', 'evidence_json', 'provider_response'
                   )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V46 constraints close lifecycle responsibility step and terminal request bindings`() {
        val definitions =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT conname || ':' || pg_get_constraintdef(oid)
                      FROM pg_constraint
                     WHERE conname IN (
                         'chk_support_resolution_trigger_state',
                         'chk_support_resolution_plan',
                         'chk_support_resolution_state',
                         'chk_support_resolution_responsibility',
                         'chk_support_resolution_step_state',
                         'chk_support_resolution_step_result',
                         'uq_support_resolution_request',
                        'uq_support_resolution_step_type',
                        'uq_support_resolution_command',
                        'fk_support_resolution_revision',
                        'fk_support_action_request_terminal_resolution',
                        'chk_support_action_request_terminal_result',
                        'chk_payment_refund_command_shape',
                        'chk_point_reservation_restoration_metadata',
                        'chk_coupon_reservation_restoration_metadata'
                     )
                     ORDER BY conname
                    """.trimIndent(),
                    String::class.java,
                ).joinToString(" ")

        assertThat(definitions).contains("PREPARING", "READY", "COMPLETED")
        assertThat(definitions).contains("PARTIALLY_RESOLVED", "UNKNOWN", "RECONCILING", "BLOCKED")
        assertThat(definitions).contains("UNDETERMINED", "STORE", "SHARED", "settlement_adjustment_krw")
        assertThat(definitions).contains("request_id", "revision_id", "terminal_resolution_id")
        assertThat(definitions).contains("SUPPORT_POST_ACCEPTANCE_RESOLUTION", "POST_ACCEPTANCE_RESOLUTION")
    }

    @Test
    fun `V46 remains applied after successor migrations`() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '46' AND success",
                Int::class.java,
            ),
        ).isOne()
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
