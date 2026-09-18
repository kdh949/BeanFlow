package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.ordering.internal.attachCurrentQuote
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
        private val orderQuoteUseCase: OrderQuoteUseCase,
    ) {
        private val requesterId = UUID.fromString("62000000-0000-0000-0000-000000000001")
        private val managerId = UUID.fromString("62000000-0000-0000-0000-000000000002")
        private val otherManagerId = UUID.fromString("62000000-0000-0000-0000-000000000003")
        private val replacementId = UUID.fromString("62000000-0000-0000-0000-000000000004")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID
        private lateinit var subjectLinkId: UUID
        private lateinit var orderId: UUID
        private var orderVersion: Long = 2

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_audit_record, operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val created = createOrder.create("support-action-request-order", orderQuoteUseCase.attachCurrentQuote(fixture.command()))
            assertThat(created.status).isEqualTo(201)
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            makeAccepted(orderId)
            seedSupportScope()
            grantRequesterPermissions()
            grantManagerPermissions(managerId)
            grantManagerPermissions(otherManagerId)
        }

        @Test
        fun `operator pickup queries use current Case or visible request without customer authentication`() {
            val actor = jwt().jwt { it.subject(requesterId.toString()) }
            val path = "/api/v1/support/cases/$caseId/orders/$orderId/pickup-slots"
            mockMvc
                .perform(get(path).with(actor))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items").isArray)
            mockMvc
                .perform(get(path.replace(caseId.toString(), UUID.randomUUID().toString())).with(actor))
                .andExpect(status().isNotFound)
            val id = requestId(createRequest("pickup-visible-request").andReturn().response.contentAsString)
            val requestPath = "/api/v1/support/action-requests/$id/pickup-slots"
            mockMvc
                .perform(get(requestPath).with(jwt().jwt { it.subject(managerId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isArray)
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'SUPPORT_ORDER_READ'",
                requesterId,
            )
            mockMvc.perform(get(path).with(actor)).andExpect(status().isForbidden)
            mockMvc.perform(get(requestPath).with(actor)).andExpect(status().isForbidden)
        }

        @Test
        fun `workflow exposes current direct actor commands and hides them after permission revoke`() {
            val id = requestId(createRequest("workflow-create-001").andReturn().response.contentAsString)
            val path = "/api/v1/support/action-requests/$id/workflow"
            mockMvc
                .perform(get(path).with(jwt().jwt { it.subject(requesterId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.allowedActions[0]").value("REVISE"))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("EXECUTE")))
                .andExpect(jsonPath("$.caseVersion").value(0))
            mockMvc
                .perform(get(path).with(jwt().jwt { it.subject(managerId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowedActions").isEmpty())
            mockMvc
                .perform(get(path).with(jwt().jwt { it.subject(requesterId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("EXECUTE")))
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'SUPPORT_ACTION_EXECUTE'",
                requesterId,
            )
            mockMvc
                .perform(get(path).with(jwt().jwt { it.subject(requesterId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.request.state").value("REASSIGNMENT_REQUIRED"))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("EXECUTE"))))
            val auditCount = jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)
            val version = jdbcTemplate.queryForObject("SELECT version FROM support_action_request WHERE id = ?", Long::class.java, id)
            mockMvc.perform(get(path).with(jwt().jwt { it.subject(requesterId.toString()) })).andExpect(status().isOk)
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isEqualTo(auditCount)
            assertThat(
                jdbcTemplate.queryForObject("SELECT version FROM support_action_request WHERE id = ?", Long::class.java, id),
            ).isEqualTo(version)
            mockMvc.perform(get(path).with(jwt().jwt { it.subject(UUID.randomUUID().toString()) })).andExpect(status().isForbidden)
        }

        @Test
        fun `create and replay produce direct ready lineage without verification or approval`() {
            jdbcTemplate.update("DELETE FROM support_verification_session WHERE id = ?", sessionId)
            val first =
                createRequest("create-action-001")
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                    .andExpect(jsonPath("$.approvalRoute").value("NONE"))
                    .andExpect(jsonPath("$.approvalSteps").isEmpty())
                    .andExpect(jsonPath("$.authorizationBasis").value("SUPPORT_DIRECT"))
                    .andExpect(jsonPath("$.verificationSessionId").doesNotExist())
                    .andReturn()
            val id = requestId(first.response.contentAsString)
            createRequest("create-action-001").andExpect(status().isCreated).andExpect(jsonPath("$.requestId").value(id.toString()))
            createRequest("create-action-001", payloadDigest = DIGEST_2)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT expires_at = created_at + interval '15 minutes' FROM support_action_revision WHERE request_id = ?",
                    Boolean::class.java,
                    id,
                ),
            ).isTrue()
        }

        @Test
        fun `legacy idempotency snapshot without new authorization fields remains readable`() {
            val created = createRequest("legacy-snapshot-key").andExpect(status().isCreated).andReturn()
            val id = requestId(created.response.contentAsString)
            jdbcTemplate.update(
                "UPDATE support_action_command_idempotency SET response_body = ((response_body::jsonb - 'authorizationBasis' - 'subjectLinkId') || jsonb_build_object('verificationSessionId', ?::text))::text WHERE request_id = ?",
                sessionId.toString(),
                id,
            )
            createRequest("legacy-snapshot-key")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.requestId").value(id.toString()))
                .andExpect(jsonPath("$.authorizationBasis").value("LEGACY"))
                .andExpect(jsonPath("$.verificationSessionId").value(sessionId.toString()))
            assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM support_action_request WHERE id = ?", Long::class.java, id),
            ).isOne()
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
                .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                .andExpect(jsonPath("$.approvalSteps").isEmpty)
            reviseRequest(requestId, "revise-action-001")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.revisionNumber").value(2))
            decideManager(requestId, managerId, "stale-revision-decision", revision = 1, expectedVersion = 0)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))
        }

        @Test
        fun `expired direct request can be revised without renewing verification`() {
            val id = requestId(createRequest("create-action-expiry").andReturn().response.contentAsString)
            jdbcTemplate.update(
                "UPDATE support_action_revision SET created_at = now() - interval '16 minutes', expires_at = now() - interval '1 minute' WHERE request_id = ?",
                id,
            )
            mockMvc
                .perform(get("/api/v1/support/action-requests/$id/workflow").with(jwt().jwt { it.subject(requesterId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("EXECUTE"))))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("REVISE")))
            reviseRequest(id, "renew-expired-direct")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("READY_FOR_EXECUTION"))
                .andExpect(jsonPath("$.revisionNumber").value(2))
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT expires_at > now() AND expires_at = created_at + interval '15 minutes' FROM support_action_revision WHERE request_id = ? AND revision_number = 2",
                    Boolean::class.java,
                    id,
                ),
            ).isTrue()
        }

        @Test
        fun `concurrent revisions consume one current request version`() {
            val id = requestId(createRequest("create-action-concurrent").andReturn().response.contentAsString)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val statuses =
                    executor
                        .invokeAll(
                            listOf(
                                Callable { reviseRequest(id, "concurrent-revision-1").andReturn().response.status },
                                Callable { reviseRequest(id, "concurrent-revision-2").andReturn().response.status },
                            ),
                        ).map { it.get() }
                        .sorted()
                assertThat(statuses).containsExactly(200, 409)
                assertThat(
                    jdbcTemplate.queryForObject("SELECT count(*) FROM support_action_revision WHERE request_id = ?", Int::class.java, id),
                ).isEqualTo(2)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `audit persistence failure rolls back revision and idempotency`() {
            val requestId = requestId(createRequest("create-action-audit-failure").andReturn().response.contentAsString)
            jdbcTemplate.update(
                """
                INSERT INTO operations_audit_record (
                    id, actor_id, actor_type, audit_category, action, target_type, target_id, occurred_at, reason,
                    before_summary, after_summary, correlation_id, source_reference, retention_expires_at,
                    retention_class, retention_policy_version_id, retention_provenance
                )
                SELECT ?, ?, 'PLATFORM_OPERATOR', audit_category, 'SUPPORT_ACTION_REVISION_CREATED',
                       target_type, target_id, occurred_at, reason, before_summary, after_summary, correlation_id,
                       ?, retention_expires_at, retention_class, retention_policy_version_id, retention_provenance
                  FROM operations_audit_record
                 WHERE action = 'SUPPORT_ACTION_REQUEST_CREATED' AND target_id = ?
                """.trimIndent(),
                UUID.randomUUID(),
                managerId.toString(),
                "support-action:$requestId:SUPPORT_ACTION_REVISION_CREATED:1",
                requestId,
            )

            reviseRequest(requestId, "manager-audit-failure")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM support_action_request WHERE id = ?",
                    String::class.java,
                    requestId,
                ),
            ).isEqualTo("READY_FOR_EXECUTION")
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
        fun `direct request cannot transfer its executor or case through reassignment`() {
            val requestId = requestId(createRequest("create-action-reassign").andReturn().response.contentAsString)
            grant(managerId, "SUPPORT_CASE_ASSIGN")
            grantReplacementPermissions(replacementId)
            repeat(2) {
                reassignRequest(requestId, managerId, replacementId, "reassign-action-001", expectedRequestVersion = 0)
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("SUPPORT_ACTION_REQUEST_STALE"))
            }
            getRequest(requestId, managerId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.executorActorId").value(requesterId.toString()))
                .andExpect(jsonPath("$.requestVersion").value(0))
            mockMvc
                .perform(get("/api/v1/support/action-requests/$requestId/workflow").with(jwt().jwt { it.subject(managerId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REASSIGN"))))
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

        @Test
        fun `historical reassignment audit failure still rolls back case and legacy request`() {
            val requestId = requestId(createRequest("create-action-reassign-guard").andReturn().response.contentAsString)
            jdbcTemplate.update(
                "UPDATE support_action_revision SET authorization_basis = 'LEGACY', verification_session_id = ?, subject_link_id = NULL WHERE request_id = ?",
                sessionId,
                requestId,
            )
            grant(managerId, "SUPPORT_CASE_ASSIGN")
            grantReplacementPermissions(managerId)

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
                "support-action:$requestId:SUPPORT_ACTION_REQUEST_REASSIGNED:2",
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
                     "subjectLinkId":"$subjectLinkId","actionPayloadDigest":"$payloadDigest",
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
                     "subjectLinkId":"$subjectLinkId","actionPayloadDigest":"$DIGEST_2",
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
            expectedRequestVersion: Long = 1,
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
            subjectLinkId = customerLinkId
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
