@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class SupportOrderChangeMigrationTest : IsolatedPostgresSupport() {
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
    fun `V45 creates support execution authorization and owner history tables without raw payload`() {
        val tables =
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_order_change_authorization', 'support_order_change_authorization_use',
                       'support_order_change_execution', 'ordering_support_order_change_history',
                       'fulfillment_pickup_reschedule_history'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            )
        assertThat(tables).hasSize(5)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_order_change_authorization', 'support_order_change_execution',
                       'ordering_support_order_change_history', 'fulfillment_pickup_reschedule_history'
                   )
                   AND column_name IN ('raw_payload', 'payload_json', 'reason_detail', 'customer_note')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V45 database constraints expose terminal state exact binding and store responsibility`() {
        val definitions =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT pg_get_constraintdef(oid)
                      FROM pg_constraint
                     WHERE conname IN (
                         'chk_support_action_request_terminal_execution',
                         'chk_support_order_change_authorization_binding',
                         'chk_support_order_change_authorization_cost',
                         'chk_support_order_change_execution_state',
                         'chk_support_order_change_execution_outcome',
                         'chk_support_order_change_execution_recovery',
                         'chk_ordering_support_order_change_state',
                         'chk_ordering_support_order_change_recovery',
                         'fk_support_action_request_terminal_execution',
                         'fk_support_order_change_authorization_use_execution',
                         'chk_order_cancellation_cause',
                         'chk_order_cancellation_reason_fields'
                     )
                     ORDER BY conname
                    """.trimIndent(),
                    String::class.java,
                ).joinToString(" ")

        assertThat(definitions).contains("EXECUTED", "RESOLUTION_REQUIRED", "CONFIRMATION", "DELEGATION")
        assertThat(definitions).contains("PREPARING", "READY", "COMPLETED", "UNKNOWN", "RECONCILING")
        assertThat(definitions).contains("STORE", "SUPPORT_REQUEST", "request_id", "authorization_id")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_notification_delivery_template'",
                String::class.java,
            ),
        ).contains("SUPPORT_PICKUP_RESCHEDULED")
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
