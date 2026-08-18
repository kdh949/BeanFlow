@file:Suppress("DEPRECATION")

package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import io.github.kdh949.beanflow.operations.api.OperatorPermission
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

internal class SupportOperationsSchemaInvariantTest : IsolatedPostgresSupport() {
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
    fun `support case state history is immutable and its state vocabulary stays closed`() {
        val caseId = insertCase()

        jdbc.update(
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
            jdbc.queryForObject(
                "SELECT count(*) FROM support_case_state_history WHERE support_case_id = ?",
                Long::class.java,
                caseId,
            ),
        ).isOne()
        assertThatThrownBy { jdbc.update("UPDATE support_case_state_history SET sequence = 1 WHERE support_case_id = ?", caseId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("DELETE FROM support_case_state_history WHERE support_case_id = ?", caseId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("UPDATE support_case SET state = 'NOT_A_CASE_STATE' WHERE id = ?", caseId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `support subject links and command replay remain unique in their documented scopes`() {
        val caseId = insertCase()
        val subjectId = UUID.randomUUID()

        jdbc.update(
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
            jdbc.update(
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

        val actorA = UUID.randomUUID()
        val actorB = UUID.randomUUID()
        insertCaseCommand(actorA, "CREATE_CASE", "shared-support-key", "a", 201)
        assertThatThrownBy { insertCaseCommand(actorA, "CREATE_CASE", "shared-support-key", "b", 201) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(insertCaseCommand(actorB, "CREATE_CASE", "shared-support-key", "c", 201)).isOne()
        assertThat(insertCaseCommand(actorA, "APPEND_NOTE", "shared-support-key", "d", 200)).isOne()
    }

    @Test
    fun `support case rejects a closed timestamp after the last aggregate change`() {
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, closed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'customer-reference', 'ORDER_STATUS', 'NORMAL', 'ORDER_STATUS_INQUIRY', 'CLOSED',
                          ?, ?, ?, ?, 1, 7)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `audit policy rows and action categories are immutable and unknown actions fail closed`() {
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_retention_policy_version", Long::class.java),
        ).isEqualTo(15)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM operations_retention_policy_head", Long::class.java),
        ).isEqualTo(10)

        assertThatThrownBy {
            jdbc.update("UPDATE operations_retention_policy_version SET duration_value = 4 WHERE policy_version_id = 1")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE operations_audit_action_category SET audit_category = 'PII_ACCESS' WHERE action = 'STOCK_RESERVED'",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertClassifiedAudit("UNDECLARED_ACTION") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `every application operator permission remains accepted by the database vocabulary`() {
        OperatorPermission.entries.forEachIndexed { index, permission ->
            jdbc.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), null, 1, ?)
                """.trimIndent(),
                UUID.nameUUIDFromBytes("central-permission:$index".toByteArray()),
                permission.name,
                "central-migration-permission:$index",
            )
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE audit_source_reference LIKE 'central-migration-permission:%'",
                Long::class.java,
            ),
        ).isEqualTo(OperatorPermission.entries.size.toLong())
    }

    private fun insertCase(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
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
        }

    private fun insertCaseCommand(
        actorId: UUID,
        operation: String,
        key: String,
        hashSeed: String,
        status: Int,
    ): Int =
        jdbc.update(
            """
            INSERT INTO support_case_command_idempotency (
                id, actor_id, operation, idempotency_key, payload_hash,
                response_status, response_body, created_at, retention_expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, '{}', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            operation,
            key,
            hashSeed.repeat(64),
            status,
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(90L * 24 * 60 * 60)),
        )

    private fun insertClassifiedAudit(action: String) {
        jdbc.update(
            """
            INSERT INTO operations_audit_record (
                id, actor_id, actor_type, action, target_type, target_id, occurred_at, reason,
                before_summary, after_summary, correlation_id, source_reference, retention_expires_at
            ) VALUES (?, 'SYSTEM', 'SYSTEM', ?, 'MIGRATION_FIXTURE', ?, ?, 'MIGRATION_FIXTURE',
                '{}', '{}', ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            action,
            UUID.randomUUID(),
            Timestamp.from(NOW),
            UUID.randomUUID().toString(),
            "central-migration:${UUID.randomUUID()}",
            Timestamp.from(NOW.plusSeconds(365L * 24 * 60 * 60)),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
    }
}
