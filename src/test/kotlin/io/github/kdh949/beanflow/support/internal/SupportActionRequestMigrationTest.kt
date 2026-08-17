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

internal class SupportActionRequestMigrationTest : IsolatedPostgresSupport() {
    companion object {
        val NOW: Instant = Instant.parse("2026-08-12T02:00:00Z")
        val REQUESTER: UUID = UUID.fromString("61000000-0000-0000-0000-000000000001")
        val MANAGER: UUID = UUID.fromString("61000000-0000-0000-0000-000000000002")
        val OPERATIONS: UUID = UUID.fromString("61000000-0000-0000-0000-000000000003")
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
    fun `V44 creates approval lineage without raw payload or evidence columns`() {
        assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'support_action_request', 'support_action_revision', 'support_action_approval_step',
                       'support_action_reassignment', 'support_action_command_idempotency',
                       'operations_support_investigation_case', 'operations_support_investigation_idempotency'
                   )
                 ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        ).hasSize(7)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('support_action_revision', 'operations_support_investigation_case')
                   AND column_name IN ('raw_payload', 'payload_json', 'raw_evidence', 'evidence_json')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `V44 rejects requester reviewer overlap and approver executor overlap`() {
        val binding = insertBinding()
        val requestId = UUID.randomUUID()

        assertThatThrownBy {
            insertRequest(
                binding,
                requestId,
                supportApprover = REQUESTER,
                operationsApprover = null,
                executor = REQUESTER,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            insertRequest(
                binding,
                requestId,
                supportApprover = MANAGER,
                operationsApprover = OPERATIONS,
                executor = MANAGER,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V44 keeps revisions and approval decisions unique and immutable`() {
        val binding = insertBinding()
        val requestId = UUID.randomUUID()
        insertRequest(binding, requestId, null, null, REQUESTER)
        val revisionId = insertRevision(binding, requestId, 1)
        val stepId = UUID.randomUUID()
        insertApprovalStep(stepId, requestId, revisionId, 1)

        assertThatThrownBy {
            insertRevision(binding, requestId, 1)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertApprovalStep(UUID.randomUUID(), requestId, revisionId, 1)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        insertRevision(binding, requestId, 2)
        assertThatThrownBy {
            insertApprovalStep(UUID.randomUUID(), requestId, revisionId, 2)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertReassignment(requestId, 3)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("UPDATE support_action_approval_step SET state = 'DENIED' WHERE id = ?", stepId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V44 operations investigation enforces reviewer separation`() {
        val binding = insertBinding()
        val requestId = UUID.randomUUID()
        insertRequest(binding, requestId, MANAGER, null, REQUESTER)
        val revisionId = insertRevision(binding, requestId, 1)

        assertThatThrownBy {
            insertInvestigation(requestId, revisionId, REQUESTER)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertInvestigation(requestId, revisionId, MANAGER)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbcTemplate.update(investigationSql(), UUID.randomUUID(), requestId, revisionId, OPERATIONS)).isOne()
    }

    @Test
    fun `V44 rejects contradictory idempotency status and failure code`() {
        val binding = insertBinding()
        val requestId = UUID.randomUUID()
        insertRequest(binding, requestId, MANAGER, null, REQUESTER)
        val revisionId = insertRevision(binding, requestId, 1)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO support_action_command_idempotency (
                    id, actor_id, operation, idempotency_key, payload_hash, request_id,
                    response_status, response_body, failure_code, created_at, retention_expires_at
                ) VALUES (?, ?, 'CREATE_REQUEST', 'invalid-outcome-1',
                          'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', ?,
                          200, '{}', 'SUPPORT_ACTION_REQUEST_STALE', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                REQUESTER,
                requestId,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(90L * 24 * 60 * 60)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        val investigationId = UUID.randomUUID()
        jdbcTemplate.update(investigationSql(), investigationId, requestId, revisionId, OPERATIONS)
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO operations_support_investigation_idempotency (
                    id, actor_id, operation, idempotency_key, payload_hash, investigation_id,
                    response_status, response_body, failure_code, created_at, retention_expires_at
                ) VALUES (?, ?, 'DECIDE', 'invalid-outcome-2',
                          'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', ?,
                          200, '{}', 'SUPPORT_ACTION_REQUEST_STALE', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                OPERATIONS,
                investigationId,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(90L * 24 * 60 * 60)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertBinding(): Binding {
        val caseId = UUID.randomUUID()
        val linkId = UUID.randomUUID()
        val subjectId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_case (
                id, requester_type, requester_reference, category, priority, reason, state,
                current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
            ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_CANCELLATION', 'NORMAL', 'ACTION_REQUEST', 'OPEN',
                      ?, ?, ?, 0, 7)
            """.trimIndent(),
            caseId,
            REQUESTER,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        jdbcTemplate.update(
            """
            INSERT INTO support_case_subject_link (
                id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
            ) VALUES (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'ACTION_SUBJECT', ?)
            """.trimIndent(),
            linkId,
            caseId,
            subjectId,
            REQUESTER,
            Timestamp.from(NOW),
        )
        jdbcTemplate.update(
            """
            INSERT INTO support_verification_session (
                id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                requested_level, state, invalid_attempts, started_at, expires_at, verified_at, version
            ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'SUPPORT_ACTION',
                      'ENHANCED', 'VERIFIED', 0, ?, ?, ?, 1)
            """.trimIndent(),
            sessionId,
            caseId,
            linkId,
            subjectId,
            REQUESTER,
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(900)),
            Timestamp.from(NOW.plusSeconds(1)),
        )
        return Binding(caseId, sessionId, targetId)
    }

    private fun insertRequest(
        binding: Binding,
        requestId: UUID,
        supportApprover: UUID?,
        operationsApprover: UUID?,
        executor: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_action_request (
                id, support_case_id, action, target_type, target_id, requester_actor_id, executor_actor_id,
                current_revision_number, approval_route, state, support_approver_actor_id,
                operations_approver_actor_id, created_at, updated_at, version
            ) VALUES (?, ?, 'ORDER_CANCELLATION', 'ORDER', ?, ?, ?, 1, 'SUPPORT_MANAGER_THEN_OPERATIONS',
                      'AWAITING_SUPPORT_MANAGER', ?, ?, ?, ?, 0)
            """.trimIndent(),
            requestId,
            binding.caseId,
            binding.targetId,
            REQUESTER,
            executor,
            supportApprover,
            operationsApprover,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun insertRevision(
        binding: Binding,
        requestId: UUID,
        number: Int,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO support_action_revision (
                id, request_id, revision_number, action, target_type, target_id, action_payload_digest,
                verification_session_id, policy_version, target_version, amount_krw, reason, evidence_digest,
                expires_at, created_by_actor_id, created_at
            ) VALUES (?, ?, ?, 'ORDER_CANCELLATION', 'ORDER', ?,
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', ?,
                      'support-action-policy/2026-08-12/v1', 7, NULL, 'CUSTOMER_REQUEST',
                      'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', ?, ?, ?)
            """.trimIndent(),
            id,
            requestId,
            number,
            binding.targetId,
            binding.sessionId,
            Timestamp.from(NOW.plusSeconds(900)),
            REQUESTER,
            Timestamp.from(NOW),
        )
        return id
    }

    private fun insertApprovalStep(
        id: UUID,
        requestId: UUID,
        revisionId: UUID,
        revisionNumber: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_action_approval_step (
                id, request_id, revision_id, revision_number, step_type, state, decided_by_actor_id,
                decision_reason, decided_at, created_at
            ) VALUES (?, ?, ?, ?, 'SUPPORT_MANAGER', 'APPROVED', ?, 'APPROVED_AFTER_REVIEW', ?, ?)
            """.trimIndent(),
            id,
            requestId,
            revisionId,
            revisionNumber,
            MANAGER,
            Timestamp.from(NOW.plusSeconds(1)),
            Timestamp.from(NOW.plusSeconds(1)),
        )
    }

    private fun insertInvestigation(
        requestId: UUID,
        revisionId: UUID,
        reviewer: UUID,
    ) {
        jdbcTemplate.update(investigationSql(), UUID.randomUUID(), requestId, revisionId, reviewer)
    }

    private fun insertReassignment(
        requestId: UUID,
        revisionNumber: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_action_reassignment (
                id, request_id, revision_number, previous_executor_actor_id, current_executor_actor_id,
                reassigned_by_actor_id, reason, case_version, request_version, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'EXECUTOR_REASSIGNED', 1, 1, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            requestId,
            revisionNumber,
            REQUESTER,
            OPERATIONS,
            MANAGER,
            Timestamp.from(NOW.plusSeconds(1)),
        )
    }

    private fun investigationSql() =
        """
        INSERT INTO operations_support_investigation_case (
            id, support_action_request_id, support_action_revision_id, revision_number, requester_actor_id,
            support_approver_actor_id, executor_actor_id, state, opened_at, expires_at, decided_by_actor_id,
            decision_reason, decision_evidence_digest, decided_at, updated_at, version
        ) VALUES (?, ?, ?, 1, '$REQUESTER', '$MANAGER', '$REQUESTER', 'APPROVED',
                  TIMESTAMPTZ '2026-08-12 02:00:00+00', TIMESTAMPTZ '2026-08-12 02:15:00+00', ?,
                  'INVESTIGATION_APPROVED', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                  TIMESTAMPTZ '2026-08-12 02:00:01+00', TIMESTAMPTZ '2026-08-12 02:00:01+00', 1)
        """.trimIndent()

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(cleanDisabled)
            .load()

    private data class Binding(
        val caseId: UUID,
        val sessionId: UUID,
        val targetId: UUID,
    )
}
