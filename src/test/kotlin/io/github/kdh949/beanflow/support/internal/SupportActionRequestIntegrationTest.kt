package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
internal class SupportActionRequestIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
    ) {
        private val requesterId = UUID.fromString("62000000-0000-0000-0000-000000000001")
        private val managerId = UUID.fromString("62000000-0000-0000-0000-000000000002")
        private val otherManagerId = UUID.fromString("62000000-0000-0000-0000-000000000003")
        private val replacementId = UUID.fromString("62000000-0000-0000-0000-000000000004")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID
        private lateinit var orderId: UUID
        private var orderVersion: Long = 2

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val created = createOrder.create("support-action-request-order", fixture.command())
            assertThat(created.status).isEqualTo(201)
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            makeAccepted(orderId)
            seedSupportScope()
            grantRequesterPermissions()
            grantManagerPermissions(managerId)
            grantManagerPermissions(otherManagerId)
        }

        @Test
        fun `create exact replay and separated manager approval produce ready lineage`() {
            val first =
                createRequest("create-action-001")
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.state").value("AWAITING_SUPPORT_MANAGER"))
                    .andExpect(jsonPath("$.approvalRoute").value("SUPPORT_MANAGER"))
                    .andExpect(jsonPath("$.revisionNumber").value(1))
                    .andExpect(jsonPath("$.requestVersion").value(0))
                    .andExpect(jsonPath("$.reason").doesNotExist())
                    .andReturn()
            val requestId = requestId(first.response.contentAsString)

            createRequest("create-action-001")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
            createRequest("create-action-001", payloadDigest = DIGEST_2)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            decideManager(requestId, managerId, "manager-approve-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                .andExpect(jsonPath("$.requestVersion").value(1))
                .andExpect(jsonPath("$.approvalSteps[0].stepType").value("SUPPORT_MANAGER"))
                .andExpect(jsonPath("$.approvalSteps[0].state").value("APPROVED"))
                .andExpect(jsonPath("$.approvalSteps[0].decidedByActorId").value(managerId.toString()))
            decideManager(requestId, managerId, "manager-approve-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
        }

        @Test
        fun `self approval and stale revision are rejected`() {
            grant(requesterId, "SUPPORT_ACTION_APPROVE")
            val requestId = requestId(createRequest("create-action-self").andReturn().response.contentAsString)

            decideManager(requestId, requesterId, "self-approve-001")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_APPROVER_MUST_DIFFER"))

            reviseRequest(requestId, "revise-action-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.state").value("AWAITING_SUPPORT_MANAGER"))
                .andExpect(jsonPath("$.approvalSteps").isEmpty)
            reviseRequest(requestId, "revise-action-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.revisionNumber").value(2))
            decideManager(requestId, managerId, "stale-revision-decision", revision = 1, expectedVersion = 0)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))
        }

        @Test
        fun `requester permission revoke and exact expiry become visible terminal failures`() {
            val revokedRequest = requestId(createRequest("create-action-revoke").andReturn().response.contentAsString)
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'SUPPORT_ORDER_CANCEL'",
                requesterId,
            )

            decideManager(revokedRequest, managerId, "approve-after-revoke")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))
            getRequest(revokedRequest, managerId)
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("STALE"))

            grant(requesterId, "SUPPORT_ORDER_CANCEL")
            val expiredRequest = requestId(createRequest("create-action-expiry").andReturn().response.contentAsString)
            jdbcTemplate.update(
                """
                UPDATE support_action_revision
                   SET created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute'
                 WHERE request_id = ?
                """.trimIndent(),
                expiredRequest,
            )
            decideManager(expiredRequest, managerId, "approve-at-expiry")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_EXPIRED"))
            getRequest(expiredRequest, managerId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("EXPIRED"))
        }

        @Test
        fun `concurrent approval allows exactly one terminal decision`() {
            val requestId = requestId(createRequest("create-action-concurrent").andReturn().response.contentAsString)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val statuses =
                    executor
                        .invokeAll(
                            listOf(
                                Callable { decideManager(requestId, managerId, "concurrent-manager-1").andReturn().response.status },
                                Callable { decideManager(requestId, otherManagerId, "concurrent-manager-2").andReturn().response.status },
                            ),
                        ).map { it.get() }
                        .sorted()

                assertThat(statuses).containsExactly(200, 409)
                assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM support_action_approval_step WHERE request_id = ? AND step_type = 'SUPPORT_MANAGER'",
                        Int::class.java,
                        requestId,
                    ),
                ).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `audit persistence failure rolls back approval and idempotency`() {
            val requestId = requestId(createRequest("create-action-audit-failure").andReturn().response.contentAsString)
            jdbcTemplate.update(
                """
                INSERT INTO operations_audit_record (
                    id, actor_id, actor_type, audit_category, action, target_type, target_id, occurred_at, reason,
                    before_summary, after_summary, correlation_id, source_reference, retention_expires_at,
                    retention_class, retention_policy_version_id, retention_provenance
                )
                SELECT ?, ?, 'PLATFORM_OPERATOR', audit_category, 'SUPPORT_ACTION_SUPPORT_MANAGER_DECIDED',
                       target_type, target_id, occurred_at, reason, before_summary, after_summary, correlation_id,
                       ?, retention_expires_at, retention_class, retention_policy_version_id, retention_provenance
                  FROM operations_audit_record
                 WHERE action = 'SUPPORT_ACTION_REQUEST_CREATED' AND target_id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                managerId.toString(),
                "support-action:$requestId:SUPPORT_ACTION_SUPPORT_MANAGER_DECIDED:1",
                requestId,
            )

            decideManager(requestId, managerId, "manager-audit-failure")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_action_request WHERE id = ?",
                    String::class.java,
                    requestId,
                ),
            ).isEqualTo("AWAITING_SUPPORT_MANAGER")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_approval_step WHERE request_id = ?",
                    Int::class.java,
                    requestId,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_command_idempotency WHERE idempotency_key = 'manager-audit-failure'",
                    Int::class.java,
                ),
            ).isZero()
        }

        @Test
        fun `revoked executor requires explicit atomic case and action reassignment`() {
            val requestId = requestId(createRequest("create-action-reassign").andReturn().response.contentAsString)
            decideManager(requestId, managerId, "approve-action-reassign").andExpect(status().isOk)
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                    "WHERE actor_id = ? AND permission = 'SUPPORT_ACTION_EXECUTE'",
                requesterId,
            )

            getRequest(requestId, managerId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REASSIGNMENT_REQUIRED"))
                .andExpect(jsonPath("$.requestVersion").value(2))

            grant(managerId, "SUPPORT_CASE_ASSIGN")
            grantReplacementPermissions(replacementId)
            reassignRequest(requestId, managerId, replacementId, "reassign-action-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                .andExpect(jsonPath("$.executorActorId").value(replacementId.toString()))
                .andExpect(jsonPath("$.requestVersion").value(3))
            reassignRequest(requestId, managerId, replacementId, "reassign-action-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.executorActorId").value(replacementId.toString()))

            assertThat(
                jdbcTemplate.queryForObject("SELECT current_assignee_id FROM support_case WHERE id = ?", UUID::class.java, caseId),
            ).isEqualTo(replacementId)
            assertThat(
                jdbcTemplate.queryForObject("SELECT version FROM support_case WHERE id = ?", Long::class.java, caseId),
            ).isEqualTo(1)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_reassignment WHERE request_id = ?",
                    Int::class.java,
                    requestId,
                ),
            ).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_case_assignment_history WHERE support_case_id = ?",
                    Int::class.java,
                    caseId,
                ),
            ).isEqualTo(2)
        }

        @Test
        fun `approver cannot become executor and reassignment audit failure rolls back both aggregates`() {
            val requestId = requestId(createRequest("create-action-reassign-guard").andReturn().response.contentAsString)
            decideManager(requestId, managerId, "approve-action-reassign-guard").andExpect(status().isOk)
            grant(managerId, "SUPPORT_CASE_ASSIGN")
            grantReplacementPermissions(managerId)

            reassignRequest(
                requestId,
                managerId,
                requesterId,
                "reassign-same-executor",
                expectedRequestVersion = 1,
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STATE_CONFLICT"))

            reassignRequest(
                requestId,
                managerId,
                managerId,
                "reassign-approver-denied",
                expectedRequestVersion = 1,
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_APPROVER_MUST_DIFFER"))

            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                    "WHERE actor_id = ? AND permission = 'SUPPORT_ACTION_EXECUTE'",
                requesterId,
            )
            getRequest(requestId, managerId).andExpect(status().isOk)
            grantReplacementPermissions(replacementId)
            jdbcTemplate.update(
                """
                INSERT INTO operations_audit_record (
                    id, actor_id, actor_type, audit_category, action, target_type, target_id, occurred_at, reason,
                    before_summary, after_summary, correlation_id, source_reference, retention_expires_at,
                    retention_class, retention_policy_version_id, retention_provenance
                )
                SELECT ?, ?, 'PLATFORM_OPERATOR', audit_category, 'SUPPORT_ACTION_REQUEST_REASSIGNED',
                       target_type, target_id, occurred_at, reason, before_summary, after_summary, correlation_id,
                       ?, retention_expires_at, retention_class, retention_policy_version_id, retention_provenance
                  FROM operations_audit_record
                 WHERE action = 'SUPPORT_ACTION_REQUEST_CREATED' AND target_id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                managerId.toString(),
                "support-action:$requestId:SUPPORT_ACTION_REQUEST_REASSIGNED:3",
                requestId,
            )

            reassignRequest(requestId, managerId, replacementId, "reassign-audit-failure")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(
                jdbcTemplate.queryForObject("SELECT state FROM support_action_request WHERE id = ?", String::class.java, requestId),
            ).isEqualTo("REASSIGNMENT_REQUIRED")
            assertThat(
                jdbcTemplate.queryForObject("SELECT current_assignee_id FROM support_case WHERE id = ?", UUID::class.java, caseId),
            ).isEqualTo(requesterId)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_action_reassignment WHERE request_id = ?",
                    Int::class.java,
                    requestId,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM support_case_assignment_history WHERE support_case_id = ?",
                    Int::class.java,
                    caseId,
                ),
            ).isOne()
        }

        private fun createRequest(
            key: String,
            payloadDigest: String = DIGEST_1,
        ) = mockMvc.perform(
            post("/api/v1/support/cases/$caseId/action-requests")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"action":"ORDER_CANCELLATION","orderId":"$orderId","expectedTargetVersion":$orderVersion,
                     "verificationSessionId":"$sessionId","actionPayloadDigest":"$payloadDigest",
                     "reason":"Customer requested cancellation","evidenceDigest":"$EVIDENCE_DIGEST"}
                    """.trimIndent(),
                ),
        )

        private fun reviseRequest(
            requestId: UUID,
            key: String,
        ) = mockMvc.perform(
            post("/api/v1/support/action-requests/$requestId/revisions")
                .with(jwt().jwt { it.subject(requesterId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedRevisionNumber":1,"expectedRequestVersion":0,"expectedTargetVersion":$orderVersion,
                     "verificationSessionId":"$sessionId","actionPayloadDigest":"$DIGEST_2",
                     "reason":"Customer reconfirmed cancellation","evidenceDigest":"$EVIDENCE_DIGEST"}
                    """.trimIndent(),
                ),
        )

        private fun decideManager(
            requestId: UUID,
            actorId: UUID,
            key: String,
            revision: Int = 1,
            expectedVersion: Long = 0,
        ) = mockMvc.perform(
            post("/api/v1/support/action-requests/$requestId/support-manager-decisions")
                .with(jwt().jwt { it.subject(actorId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"revisionNumber":$revision,"expectedRequestVersion":$expectedVersion,
                     "decision":"APPROVE","reason":"Policy and evidence reviewed"}
                    """.trimIndent(),
                ),
        )

        private fun getRequest(
            requestId: UUID,
            actorId: UUID,
        ) = mockMvc.perform(
            get("/api/v1/support/action-requests/$requestId")
                .with(jwt().jwt { it.subject(actorId.toString()) }),
        )

        private fun reassignRequest(
            requestId: UUID,
            actorId: UUID,
            assigneeId: UUID,
            key: String,
            expectedRequestVersion: Long = 2,
        ) = mockMvc.perform(
            post("/api/v1/support/action-requests/$requestId/reassignments")
                .with(jwt().jwt { it.subject(actorId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"revisionNumber":1,"expectedRequestVersion":$expectedRequestVersion,"expectedCaseVersion":0,
                     "assigneeId":"$assigneeId","reason":"Original executor permission was revoked"}
                    """.trimIndent(),
                ),
        )

        private fun makeAccepted(id: UUID) {
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET state = 'ACCEPTED',
                       reservation_expires_at = NULL,
                       paid_at = created_at + interval '1 second',
                       acceptance_warning_at = created_at + interval '121 seconds',
                       acceptance_deadline_at = created_at + interval '181 seconds',
                       accepted_at = created_at + interval '120 seconds',
                       updated_at = created_at + interval '120 seconds',
                       version = ?
                 WHERE id = ?
                """.trimIndent(),
                orderVersion,
                id,
            )
        }

        private fun seedSupportScope() {
            val now = Instant.now().minusSeconds(30)
            caseId = UUID.randomUUID()
            val customerLinkId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_CANCELLATION', 'NORMAL', 'ACTION_REQUEST', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_assignment_history (
                    id, support_case_id, sequence, previous_assignee_id, current_assignee_id,
                    actor_id, case_version, occurred_at
                ) VALUES (?, ?, 0, NULL, ?, ?, 0, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                caseId,
                requesterId,
                requesterId,
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
                customerLinkId,
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
                customerLinkId,
                fixture.customerId,
                requesterId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(900)),
                Timestamp.from(now.plusSeconds(1)),
            )
        }

        private fun grantRequesterPermissions() {
            listOf(
                "SUPPORT_CASE_READ",
                "SUPPORT_ORDER_READ",
                "SUPPORT_ACTION_REQUEST",
                "SUPPORT_ACTION_EXECUTE",
                "SUPPORT_ORDER_CANCEL",
            ).forEach { grant(requesterId, it) }
        }

        private fun grantManagerPermissions(actorId: UUID) {
            listOf("SUPPORT_CASE_READ", "SUPPORT_ORDER_READ", "SUPPORT_ACTION_APPROVE").forEach { grant(actorId, it) }
        }

        private fun grantReplacementPermissions(actorId: UUID) {
            listOf("SUPPORT_CASE_WRITE", "SUPPORT_ACTION_EXECUTE", "SUPPORT_ORDER_CANCEL").forEach { grant(actorId, it) }
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
                "support-action-request:$permission:$actorId:${UUID.randomUUID()}",
            )
        }

        private fun requestId(body: String): UUID =
            UUID.fromString(requireNotNull(Regex("\\\"requestId\\\":\\\"([^\\\"]+)\\\"").find(body)).groupValues[1])

        private companion object {
            const val DIGEST_1 = "1111111111111111111111111111111111111111111111111111111111111111"
            const val DIGEST_2 = "2222222222222222222222222222222222222222222222222222222222222222"
            const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        }
    }
