package io.github.kdh949.beanflow.identity.internal

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class CustomerAccountMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(false).clean()
        flyway().migrate()
    }

    @Test
    fun `V53 creates customer and HMAC-only login attempt contracts without backfill`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('identity_customer_account', 'identity_login_attempt')
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("identity_customer_account", "identity_login_attempt")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_customer_account", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isZero()
        assertThat(
            jdbc.queryForObject("SELECT max(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Int::class.java),
        ).isEqualTo(53)
    }

    @Test
    fun `database rejects invalid login lock and raw attempt shapes`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        insertAccount("valid.user", "ACTIVE", null, now)
        assertThatThrownBy { insertAccount("Invalid User", "ACTIVE", null, now) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertAccount("locked.user", "LOCKED", null, now) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO identity_login_attempt
                    (id, actor_type, scope_type, scope_hmac, window_start, failure_count, blocked_until, updated_at)
                VALUES (?, 'CUSTOMER', 'LOGIN_ID', ?, ?, 5, NULL, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                "a".repeat(64),
                Timestamp.from(now),
                Timestamp.from(now),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'identity_login_attempt' ORDER BY ordinal_position",
                String::class.java,
            ),
        ).doesNotContain("login_id", "ip_address", "raw_value")
    }

    private fun insertAccount(
        loginId: String,
        state: String,
        lockedUntil: Instant?,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_customer_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 locked_until, created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Migration Test', ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            loginId,
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$fixture\$fixture",
            state,
            lockedUntil?.let(Timestamp::from),
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()
}
