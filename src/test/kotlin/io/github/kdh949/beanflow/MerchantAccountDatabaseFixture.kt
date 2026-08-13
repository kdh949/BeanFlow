package io.github.kdh949.beanflow

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal object MerchantAccountDatabaseFixture {
    fun insertActive(
        jdbcTemplate: JdbcTemplate,
        actorId: UUID,
    ) {
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO identity_merchant_account (
                id, login_id, password_hash, credential_version, display_name, state,
                temporary_password_expires_at, password_changed_at, locked_until,
                created_at, updated_at, version
            ) VALUES (?, ?, 'test-only-password-hash', 0, 'Test merchant actor', 'ACTIVE',
                      null, ?, null, ?, ?, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            actorId,
            "test.${actorId.toString().replace("-", "").take(20)}",
            now,
            now,
            now,
        )
    }
}
