package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.operations.api.OpenOperationsSupportInvestigationCommand
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationOperations
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
internal class OperationsSupportInvestigationIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
        private val investigationOperations: OperationsSupportInvestigationOperations,
    ) {
        private val requesterId = UUID.fromString("63000000-0000-0000-0000-000000000001")
        private val managerId = UUID.fromString("63000000-0000-0000-0000-000000000002")
        private val operationsId = UUID.fromString("63000000-0000-0000-0000-000000000003")
        private val otherOperationsId = UUID.fromString("63000000-0000-0000-0000-000000000004")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID
        private lateinit var sessionExpiresAt: Instant
        private lateinit var orderId: UUID
        private var orderVersion: Long = 2

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE operations_support_investigation_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val created = createOrder.create("operations-support-investigation-order", fixture.command())
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            makeAccepted(orderId)
            seedSupportScope()
            listOf("SUPPORT_ACTION_REQUEST", "SUPPORT_ACTION_EXECUTE", "SUPPORT_ORDER_CANCEL").forEach { grant(requesterId, it) }
            listOf(requesterId, managerId, operationsId, otherOperationsId).forEach {
                grant(it, "OPERATIONS_SUPPORT_INVESTIGATION")
            }
        }

        @Test
        fun `requester manager and executor cannot review while separated Operations approves exactly once`() {
            val binding = seedRequest(withManager = true)
            val investigationId = open(binding)

            decide(investigationId, requesterId, "investigation-self-review")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_APPROVER_MUST_DIFFER"))
            decide(investigationId, managerId, "investigation-manager-dual-role")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_APPROVER_MUST_DIFFER"))

            decide(investigationId, operationsId, "investigation-approve-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.supportRequestState").value("READY_FOR_EXECUTION"))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.version").value(1))
            decide(investigationId, operationsId, "investigation-approve-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("APPROVED"))
            decide(investigationId, operationsId, "investigation-approve-001", decision = "DENY")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            assertThat(state("support_action_request", binding.requestId)).isEqualTo("READY_FOR_EXECUTION")
            assertThat(state("operations_support_investigation_case", investigationId)).isEqualTo("APPROVED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_approval_step WHERE request_id = ? AND step_type = 'OPERATIONS' AND state = 'APPROVED'",
                    Int::class.java,
                    binding.requestId,
                ),
            ).isOne()
        }

        @Test
        fun `Operations return requires a new revision and cannot be decided twice`() {
            val binding = seedRequest(withManager = false)
            val investigationId = open(binding)

            decide(
                investigationId,
                operationsId,
                "investigation-return-001",
                decision = "RETURN_FOR_REVISION",
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("RETURNED"))
                .andExpect(jsonPath("$.supportRequestState").value("REVISION_REQUIRED"))
            decide(investigationId, otherOperationsId, "investigation-second-decision")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))

            assertThat(state("support_action_request", binding.requestId)).isEqualTo("REVISION_REQUIRED")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_revision WHERE request_id = ?",
                    Int::class.java,
                    binding.requestId,
                ),
            ).isOne()
        }

        @Test
        fun `permission revoke leaves investigation open and target version change stales both owners`() {
            val revoked = seedRequest(withManager = false)
            val revokedInvestigation = open(revoked)
            revoke(operationsId, "OPERATIONS_SUPPORT_INVESTIGATION")

            decide(revokedInvestigation, operationsId, "investigation-after-revoke")
                .andExpect(status().isForbidden)
            assertThat(state("operations_support_investigation_case", revokedInvestigation)).isEqualTo("OPEN")
            assertThat(state("support_action_request", revoked.requestId)).isEqualTo("AWAITING_OPERATIONS")

            grant(operationsId, "OPERATIONS_SUPPORT_INVESTIGATION")
            val stale = seedRequest(withManager = false)
            val staleInvestigation = open(stale)
            jdbcTemplate.update("UPDATE ordering_order SET version = version + 1 WHERE id = ?", orderId)
            decide(staleInvestigation, operationsId, "investigation-target-stale")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))
            assertThat(state("operations_support_investigation_case", staleInvestigation)).isEqualTo("STALE")
            assertThat(state("support_action_request", stale.requestId)).isEqualTo("STALE")
        }

        @Test
        fun `concurrent Operations review allows one decision`() {
            val binding = seedRequest(withManager = false)
            val investigationId = open(binding)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val statuses =
                    executor
                        .invokeAll(
                            listOf(
                                Callable {
                                    decide(investigationId, operationsId, "operations-concurrent-1")
                                        .andReturn()
                                        .response.status
                                },
                                Callable {
                                    decide(investigationId, otherOperationsId, "operations-concurrent-2")
                                        .andReturn()
                                        .response.status
                                },
                            ),
                        ).map { it.get() }
                        .sorted()
                assertThat(statuses).containsExactly(200, 409)
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM support_action_approval_step WHERE request_id = ? AND step_type = 'OPERATIONS'",
                        Int::class.java,
                        binding.requestId,
                    ),
                ).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `Support callback Audit failure rolls back both owner states and decision idempotency`() {
            val binding = seedRequest(withManager = false)
            val investigationId = open(binding)
            jdbcTemplate.update(
                """
                INSERT INTO operations_audit_record (
                    id, actor_id, actor_type, audit_category, action, target_type, target_id, occurred_at, reason,
                    before_summary, after_summary, correlation_id, source_reference, retention_expires_at,
                    retention_class, retention_policy_version_id, retention_provenance
                )
                SELECT ?, ?, 'PLATFORM_OPERATOR', audit_category, 'SUPPORT_ACTION_OPERATIONS_DECIDED',
                       'SUPPORT_ACTION_REQUEST', ?, occurred_at, reason, before_summary, after_summary, correlation_id,
                       ?, retention_expires_at, retention_class, retention_policy_version_id, retention_provenance
                  FROM operations_audit_record
                 WHERE action = 'OPERATIONS_SUPPORT_INVESTIGATION_OPENED' AND target_id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                operationsId.toString(),
                binding.requestId,
                "support-action:${binding.requestId}:SUPPORT_ACTION_OPERATIONS_DECIDED:1",
                investigationId,
            )

            decide(investigationId, operationsId, "operations-audit-failure")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            assertThat(state("operations_support_investigation_case", investigationId)).isEqualTo("OPEN")
            assertThat(state("support_action_request", binding.requestId)).isEqualTo("AWAITING_OPERATIONS")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_approval_step WHERE request_id = ? AND step_type = 'OPERATIONS'",
                    Int::class.java,
                    binding.requestId,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_support_investigation_idempotency WHERE idempotency_key = 'operations-audit-failure'",
                    Int::class.java,
                ),
            ).isZero()
        }

        private fun seedRequest(withManager: Boolean): Binding {
            val now = Instant.now().minusSeconds(5)
            val requestId = UUID.randomUUID()
            val revisionId = UUID.randomUUID()
            val manager = if (withManager) managerId else null
            val route = if (withManager) "SUPPORT_MANAGER_THEN_OPERATIONS" else "OPERATIONS"
            jdbcTemplate.update(
                """
                INSERT INTO support_action_request (
                    id, support_case_id, action, target_type, target_id, requester_actor_id, executor_actor_id,
                    current_revision_number, approval_route, state, support_approver_actor_id,
                    operations_approver_actor_id, created_at, updated_at, version
                ) VALUES (?, ?, 'ORDER_CANCELLATION', 'ORDER', ?, ?, ?, 1, ?, 'AWAITING_OPERATIONS', ?, NULL, ?, ?, 0)
                """.trimIndent(),
                requestId,
                caseId,
                orderId,
                requesterId,
                requesterId,
                route,
                manager,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_action_revision (
                    id, request_id, revision_number, action, target_type, target_id, action_payload_digest,
                    verification_session_id, policy_version, target_version, amount_krw, reason, evidence_digest,
                    expires_at, created_by_actor_id, created_at
                ) VALUES (?, ?, 1, 'ORDER_CANCELLATION', 'ORDER', ?, ?, ?,
                          'support-action-policy/2026-08-12/v1', ?, NULL, 'CUSTOMER_REQUEST', ?, ?, ?, ?)
                """.trimIndent(),
                revisionId,
                requestId,
                orderId,
                PAYLOAD_DIGEST,
                sessionId,
                orderVersion,
                EVIDENCE_DIGEST,
                Timestamp.from(sessionExpiresAt),
                requesterId,
                Timestamp.from(now),
            )
            if (manager != null) {
                jdbcTemplate.update(
                    """
                    INSERT INTO support_action_approval_step (
                        id, request_id, revision_id, revision_number, step_type, state, decided_by_actor_id,
                        decision_reason, decided_at, created_at
                    ) VALUES (?, ?, ?, 1, 'SUPPORT_MANAGER', 'APPROVED', ?, 'MANAGER_APPROVED', ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    requestId,
                    revisionId,
                    manager,
                    Timestamp.from(now),
                    Timestamp.from(now),
                )
            }
            return Binding(requestId, revisionId, manager, sessionExpiresAt)
        }

        private fun open(binding: Binding): UUID =
            investigationOperations
                .open(
                    OpenOperationsSupportInvestigationCommand(
                        binding.requestId,
                        binding.revisionId,
                        1,
                        requesterId,
                        binding.managerId,
                        requesterId,
                        binding.expiresAt,
                        Instant.now(),
                    ),
                ).investigationId

        private fun decide(
            investigationId: UUID,
            actorId: UUID,
            key: String,
            decision: String = "APPROVE",
        ) = mockMvc.perform(
            post("/api/v1/operations/investigations/$investigationId/decisions")
                .with(
                    jwt()
                        .jwt { it.subject(actorId.toString()) }
                        .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")),
                ).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":0,"decision":"$decision","reason":"Investigation evidence reviewed",
                     "evidenceDigest":"$EVIDENCE_DIGEST"}
                    """.trimIndent(),
                ),
        )

        private fun state(
            table: String,
            id: UUID,
        ): String = requireNotNull(jdbcTemplate.queryForObject("SELECT state FROM $table WHERE id = ?", String::class.java, id))

        private fun revoke(
            actorId: UUID,
            permission: String,
        ) {
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = ?",
                actorId,
                permission,
            )
        }

        private fun grant(
            actorId: UUID,
            permission: String,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                ON CONFLICT (actor_id, permission) DO UPDATE
                    SET state = 'ACTIVE', revoked_at = NULL, version = operations_operator_permission_grant.version + 1,
                        audit_source_reference = EXCLUDED.audit_source_reference
                """.trimIndent(),
                actorId,
                permission,
                "operations-investigation:$permission:$actorId:${UUID.randomUUID()}",
            )
        }

        private fun makeAccepted(id: UUID) {
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET state = 'ACCEPTED', reservation_expires_at = NULL,
                       paid_at = created_at + interval '1 second',
                       acceptance_warning_at = created_at + interval '121 seconds',
                       acceptance_deadline_at = created_at + interval '181 seconds',
                       accepted_at = created_at + interval '120 seconds',
                       updated_at = created_at + interval '120 seconds', version = ?
                 WHERE id = ?
                """.trimIndent(),
                orderVersion,
                id,
            )
        }

        private fun seedSupportScope() {
            val now = Instant.now().minusSeconds(30)
            sessionExpiresAt = now.plusSeconds(900)
            caseId = UUID.randomUUID()
            val linkId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_CANCELLATION', 'NORMAL', 'INVESTIGATION', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES
                    (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'ACTION_SUBJECT', ?),
                    (?, ?, 'ORDER', ?, 'RELATED_ORDER', ?, 'ACTION_TARGET', ?)
                """.trimIndent(),
                linkId,
                caseId,
                fixture.customerId,
                requesterId,
                Timestamp.from(now),
                UUID.randomUUID(),
                caseId,
                orderId,
                requesterId,
                Timestamp.from(now),
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
                fixture.customerId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(sessionExpiresAt),
                Timestamp.from(now.plusSeconds(1)),
            )
        }

        private data class Binding(
            val requestId: UUID,
            val revisionId: UUID,
            val managerId: UUID?,
            val expiresAt: Instant,
        )

        private companion object {
            const val PAYLOAD_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        }
    }
