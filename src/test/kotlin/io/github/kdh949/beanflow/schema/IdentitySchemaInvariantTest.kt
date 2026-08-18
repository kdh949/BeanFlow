package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class IdentitySchemaInvariantTest : IsolatedPostgresSupport() {
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
    fun `customer login attempt schema stores only HMAC scope material`() {
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
        assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'identity_login_attempt'",
                String::class.java,
            ),
        ).doesNotContain("login_id", "ip_address", "raw_value")
    }

    @Test
    fun `customer account rejects invalid login lock and raw attempt shapes`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        insertCustomer("valid.user", "ACTIVE", null, now)
        assertThatThrownBy { insertCustomer("Invalid User", "ACTIVE", null, now) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertCustomer("locked.user", "LOCKED", null, now) }
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
    }

    @Test
    fun `merchant credential schema extends only the accepted audit actor vocabulary`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'identity_merchant_account',
                       'operations_merchant_credential_command_idempotency'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly(
            "identity_merchant_account",
            "operations_merchant_credential_command_idempotency",
        )
        assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_audit_actor_type'",
                String::class.java,
            ),
        ).contains("MERCHANT").contains("STORE_OWNER").doesNotContain("MERCHANT_ADMIN")
    }

    @Test
    fun `merchant account rejects invalid lifecycle and protects terminal command outcomes`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val accountId = UUID.randomUUID()
        insertMerchant(accountId, "merchant.user", "INITIAL_PASSWORD", now.plusSeconds(86_400), null, now)

        assertThatThrownBy {
            insertMerchant(UUID.randomUUID(), "invalid lifecycle", "ACTIVE", null, now, now)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertMerchant(UUID.randomUUID(), "expired.user", "EXPIRED", null, null, now)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        val commandId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO operations_merchant_credential_command_idempotency
                (id, operator_id, operation, idempotency_key, payload_hash,
                 merchant_account_id, outcome, created_at, retention_expires_at)
            VALUES (?, ?, 'CREATE', 'merchant-create-0001', ?, ?, 'ACCOUNT_CREATED', ?, ?)
            """.trimIndent(),
            commandId,
            UUID.randomUUID(),
            "a".repeat(64),
            accountId,
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(90L * 24 * 60 * 60)),
        )
        assertThatThrownBy {
            jdbc.update(
                "UPDATE operations_merchant_credential_command_idempotency SET payload_hash = ? WHERE id = ?",
                "b".repeat(64),
                commandId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertCustomer(
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

    private fun insertMerchant(
        id: UUID,
        loginId: String,
        state: String,
        temporaryPasswordExpiresAt: Instant?,
        passwordChangedAt: Instant?,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Migration Merchant', ?, ?, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            id,
            loginId,
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$fixture\$fixture",
            state,
            temporaryPasswordExpiresAt?.let(Timestamp::from),
            passwordChangedAt?.let(Timestamp::from),
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }
}
