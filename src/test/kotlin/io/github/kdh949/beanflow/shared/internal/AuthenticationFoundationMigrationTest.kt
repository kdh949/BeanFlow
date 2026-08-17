package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class AuthenticationFoundationMigrationTest : IsolatedPostgresSupport() {
    companion object {
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun cleanDatabase() {
        flyway(cleanDisabled = false).clean()
    }

    @Test
    fun `V52 creates the official PostgreSQL Spring Session schema and indexes`() {
        flyway().migrate()

        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('spring_session', 'spring_session_attributes')
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("spring_session", "spring_session_attributes")
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'spring_session'
                 ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "primary_id",
            "session_id",
            "creation_time",
            "last_access_time",
            "max_inactive_interval",
            "expiry_time",
            "principal_name",
        )
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'spring_session'
                 ORDER BY indexname
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "spring_session_ix1",
            "spring_session_ix2",
            "spring_session_ix3",
            "spring_session_pk",
        )
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.table_constraints
                 WHERE table_schema = 'public'
                   AND table_name = 'spring_session_attributes'
                   AND constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isOne()
    }

    @Test
    fun `V52 expands only the closed permission vocabulary without granting it`() {
        flyway().migrate()

        assertThat(OperatorPermission.entries.map(OperatorPermission::name))
            .contains("MERCHANT_CREDENTIAL_MANAGE")
        jdbcTemplate.update(
            """
            INSERT INTO operations_operator_permission_grant (
                actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
            ) VALUES (?, 'MERCHANT_CREDENTIAL_MANAGE', 'ACTIVE', now(), null, 1, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            "authentication-foundation-test:${UUID.randomUUID()}",
        )
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE permission = 'MERCHANT_CREDENTIAL_MANAGE'",
                Long::class.java,
            ),
        ).isOne()
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, 'MERCHANT_CREDENTIAL_ADMIN', 'ACTIVE', now(), null, 1, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                "authentication-foundation-test:${UUID.randomUUID()}",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V52 remains applied and does not seed a merchant credential grant`() {
        flyway().migrate()

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '52'",
                Int::class.java,
            ),
        ).isOne()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE permission = 'MERCHANT_CREDENTIAL_MANAGE'",
                Long::class.java,
            ),
        ).isZero()
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
