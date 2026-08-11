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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
internal class SupportCaseMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(BEANFLOW_POSTGRES_IMAGE)

        val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
    }

    private val jdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetToV39() {
        flyway(cleanDisabled = false).clean()
        flyway(target = "39").migrate()
        flyway().migrate()
    }

    @Test
    fun `V40 creates support case foundation with closed vocabularies and immutable histories`() {
        val caseId = insertCase()

        jdbcTemplate.update(
            """
            INSERT INTO support_case_state_history (
                id, support_case_id, sequence, previous_state, current_state, actor_id, case_version, occurred_at
            ) VALUES (?, ?, 0, NULL, 'OPEN', ?, 0, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            caseId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
        )

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_case_state_history WHERE support_case_id = ?",
                Long::class.java,
                caseId,
            ),
        ).isOne()
        assertThatThrownBy {
            jdbcTemplate.update("UPDATE support_case_state_history SET sequence = 1 WHERE support_case_id = ?", caseId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM support_case_state_history WHERE support_case_id = ?", caseId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE support_case SET state = 'NOT_A_CASE_STATE' WHERE id = ?",
                caseId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'customer-reference', 'ORDER_STATUS', 'NORMAL', 'ORDER_STATUS_INQUIRY', 'OPEN',
                          ?, ?, ?, 0, 999999)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE permission LIKE 'SUPPORT_CASE_%'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V40 enforces active subject link and command idempotency uniqueness`() {
        val caseId = insertCase()
        val subjectId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO support_case_subject_link (
                id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
            ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'SUBJECT_LINKED', ?)
            """.trimIndent(),
            UUID.randomUUID(),
            caseId,
            subjectId,
            UUID.randomUUID(),
            Timestamp.from(NOW),
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'SUBJECT_LINKED', ?)
                """.trimIndent(),
                UUID.randomUUID(),
                caseId,
                subjectId,
                UUID.randomUUID(),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        jdbcTemplate.update(
            """
            INSERT INTO support_case_command_idempotency (
                idempotency_key, operation, payload_hash, response_status, response_body, created_at
            ) VALUES ('support-case-key-001', 'CREATE_CASE', ?, 201, '{}', ?)
            """.trimIndent(),
            "a".repeat(64),
            Timestamp.from(NOW),
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_case_command_idempotency (
                    idempotency_key, operation, payload_hash, response_status, response_body, created_at
                ) VALUES ('support-case-key-001', 'APPEND_NOTE', ?, 201, '{}', ?)
                """.trimIndent(),
                "b".repeat(64),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertCase(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_case (
                id, requester_type, requester_reference, category, priority, reason, state,
                current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
            ) VALUES (?, 'CUSTOMER', 'customer-reference', 'ORDER_STATUS', 'NORMAL', 'ORDER_STATUS_INQUIRY', 'OPEN',
                      ?, ?, ?, 0, 7)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        return id
    }

    private fun flyway(
        target: String? = null,
        cleanDisabled: Boolean = true,
    ): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .cleanDisabled(cleanDisabled)
        target?.let(configuration::target)
        return configuration.load()
    }
}
