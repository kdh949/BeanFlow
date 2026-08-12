@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BEANFLOW_POSTGRES_IMAGE
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
internal class SupportOrderChangeMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
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
                         'chk_order_cancellation_cause',
                         'chk_order_cancellation_reason_fields'
                     )
                     ORDER BY conname
                    """.trimIndent(),
                    String::class.java,
                ).joinToString(" ")

        assertThat(definitions).contains("EXECUTED", "RESOLUTION_REQUIRED", "CONFIRMATION", "DELEGATION")
        assertThat(definitions).contains("STORE", "SUPPORT_REQUEST")
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
