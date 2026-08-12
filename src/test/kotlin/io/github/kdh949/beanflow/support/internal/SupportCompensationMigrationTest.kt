@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

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

@Testcontainers(disabledWithoutDocker = true)
internal class SupportCompensationMigrationTest {
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
    fun `V47 creates owner-separated goodwill schema without raw evidence or PII`() {
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_compensation_policy_head', 'support_compensation_policy_version',
                       'support_compensation_limit_rule', 'support_compensation_request',
                       'support_compensation_terminal_benefit', 'support_compensation_limit_lock',
                       'support_compensation_limit_consumption', 'support_compensation_command_idempotency',
                       'loyalty_goodwill_point_issuance', 'loyalty_goodwill_point_funding_leg',
                       'promotion_goodwill_coupon_template', 'promotion_goodwill_coupon_issuance'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).hasSize(12)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND (table_name LIKE '%goodwill%' OR table_name LIKE 'support_compensation%')
                   AND column_name IN (
                       'raw_payload', 'payload_json', 'raw_evidence', 'evidence_json',
                       'customer_name', 'phone', 'email', 'provider_response'
                   )
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V47 seeds immutable policy limits and coupon templates`() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_compensation_limit_rule WHERE policy_version_id = '90000000-0000-0000-0000-000000000001'",
                Int::class.java,
            ),
        ).isEqualTo(5)
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT fixed_amount_krw FROM promotion_goodwill_coupon_template ORDER BY fixed_amount_krw",
                Long::class.java,
            ),
        ).containsExactly(3_000L, 10_000L, 30_000L)

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE support_compensation_policy_version SET low_amount_maximum_krw = 1 WHERE id = '90000000-0000-0000-0000-000000000001'",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "DELETE FROM promotion_goodwill_coupon_template WHERE id = '91000000-0000-0000-0000-000000000003'",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V47 exposes terminal rolling owner and action binding constraints`() {
        val definitions =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT conname || ':' || pg_get_constraintdef(oid)
                      FROM pg_constraint
                     WHERE conname IN (
                         'uq_support_compensation_incident_terminal',
                         'uq_support_compensation_request_terminal',
                         'uq_support_compensation_consumption_scope',
                         'uq_support_compensation_command',
                         'fk_support_action_request_terminal_compensation',
                         'chk_support_action_request_terminal_result',
                         'chk_point_transaction_balance_effect',
                         'chk_point_transaction_restoration_metadata'
                     )
                     ORDER BY conname
                    """.trimIndent(),
                    String::class.java,
                ).joinToString(" ")

        assertThat(definitions)
            .contains("incident_id", "request_id", "scope", "terminal_compensation_id")
            .contains("GOODWILL_COMPENSATION", "CREDIT")
    }

    @Test
    fun `V48 is the latest Flyway version`() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success",
                Int::class.java,
            ),
        ).isEqualTo(48)
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
