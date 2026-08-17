@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class SupportVerificationMigrationTest : IsolatedPostgresSupport() {
    companion object {
        val NOW: Instant = Instant.parse("2026-08-12T00:00:00Z")
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
    fun `V42 creates closed verification and grant vocabularies without secret columns`() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE 'support_%'",
                Long::class.java,
            ),
        ).isGreaterThanOrEqualTo(20)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('support_verification_session', 'support_verification_challenge', 'support_verification_attempt')
                   AND (column_name LIKE '%otp%' OR column_name LIKE '%proof%' OR column_name LIKE '%raw_link%')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE permission = 'PRIVACY_BREAK_GLASS_REVIEW'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V42 enforces exact session binding ttl and append-only attempts`() {
        val binding = insertBinding()
        val sessionId = UUID.randomUUID()
        insertSession(sessionId, binding)
        val challengeId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_verification_challenge (
                id, session_id, channel, state, opaque_provider_reference, requested_at, expires_at, version
            ) VALUES (?, ?, 'REGISTERED_PHONE', 'VERIFIED', 'opaque-reference', ?, ?, 1)
            """.trimIndent(),
            challengeId,
            sessionId,
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(300)),
        )
        val attemptId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_verification_attempt (id, session_id, challenge_id, actor_id, channel, outcome, occurred_at)
            VALUES (?, ?, ?, ?, 'REGISTERED_PHONE', 'VERIFIED', ?)
            """.trimIndent(),
            attemptId,
            sessionId,
            challengeId,
            UUID.randomUUID(),
            Timestamp.from(NOW.plusSeconds(1)),
        )

        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM support_verification_attempt WHERE id = ?", attemptId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_session (
                    id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                    requested_level, state, invalid_attempts, started_at, expires_at, version
                ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'PERSONAL_DATA_REVEAL',
                          'BASIC', 'PENDING', 0, ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                binding.caseId,
                binding.linkId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(900)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE support_verification_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(NOW.plusSeconds(899)),
                sessionId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V42 enforces grant field and subject compatibility through binding`() {
        val binding = insertBinding()
        val sessionId = UUID.randomUUID()
        insertSession(sessionId, binding)
        jdbcTemplate.update(
            """
            UPDATE support_verification_session
               SET state = 'VERIFIED', verified_at = ?, version = 1
             WHERE id = ?
            """.trimIndent(),
            Timestamp.from(NOW.plusSeconds(1)),
            sessionId,
        )
        val grantId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_data_access_grant (
                id, support_case_id, subject_link_id, subject_type, subject_id, requester_id,
                verification_session_id, purpose, reason_code, risk, state, max_reveals, reserved_reveals,
                requested_at, expires_at, version
            ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, ?, 'CASE_RESOLUTION', 'CASE_HANDLING',
                      'BASIC', 'ACTIVE', 3, 0, ?, ?, 0)
            """.trimIndent(),
            grantId,
            binding.caseId,
            binding.linkId,
            binding.subjectId,
            UUID.randomUUID(),
            sessionId,
            Timestamp.from(NOW.plusSeconds(1)),
            Timestamp.from(NOW.plusSeconds(601)),
        )
        jdbcTemplate.update(
            "INSERT INTO support_data_access_grant_field (grant_id, field) VALUES (?, 'CUSTOMER_DISPLAY_NAME')",
            grantId,
        )

        assertThatThrownBy {
            jdbcTemplate.update(
                "INSERT INTO support_data_access_grant_field (grant_id, field) VALUES (?, 'UNBOUNDED_RAW_PROFILE')",
                grantId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertBinding(): Binding {
        val caseId = UUID.randomUUID()
        val linkId = UUID.randomUUID()
        val subjectId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_case (
                id, requester_type, requester_reference, category, priority, reason, state,
                current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
            ) VALUES (?, 'CUSTOMER', 'customer-reference', 'ACCOUNT_RECOVERY', 'NORMAL', 'ACCOUNT_ACCESS_CASE', 'OPEN',
                      ?, ?, ?, 0, 7)
            """.trimIndent(),
            caseId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        jdbcTemplate.update(
            """
            INSERT INTO support_case_subject_link (
                id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
            ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'IDENTITY_SUBJECT', ?)
            """.trimIndent(),
            linkId,
            caseId,
            subjectId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
        )
        return Binding(caseId, linkId, subjectId)
    }

    private fun insertSession(
        sessionId: UUID,
        binding: Binding,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_verification_session (
                id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                requested_level, state, invalid_attempts, started_at, expires_at, version
            ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'PERSONAL_DATA_REVEAL',
                      'BASIC', 'PENDING', 0, ?, ?, 0)
            """.trimIndent(),
            sessionId,
            binding.caseId,
            binding.linkId,
            binding.subjectId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(900)),
        )
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()

    private data class Binding(
        val caseId: UUID,
        val linkId: UUID,
        val subjectId: UUID,
    )
}
