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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

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
internal class SupportActionEvaluationIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
        private val orderQuoteUseCase: OrderQuoteUseCase,
    ) {
        private val actorId = UUID.fromString("52000000-0000-0000-0000-000000000001")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var caseId: UUID
        private lateinit var customerLinkId: UUID
        private lateinit var orderLinkId: UUID
        private lateinit var sessionId: UUID
        private lateinit var orderId: UUID

        @BeforeEach
        fun resetAndSeed() {
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val created = createOrder.create("support-action-evaluation-order", orderQuoteUseCase.attachCurrentQuote(fixture.command()))
            assertThat(created.status).isEqualTo(201)
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            seedSupportScope()
            grant("SUPPORT_CASE_READ")
            grant("SUPPORT_ORDER_READ")
            grant("SUPPORT_ACTION_REQUEST")
            grant("SUPPORT_ORDER_CANCEL")
        }

        @Test
        fun `pending payment cancellation is allowed with current action bound verification`() {
            evaluate(expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("POLICY_ALLOWED"))
                .andExpect(jsonPath("$.requiredVerificationLevel").value("BASIC"))
                .andExpect(jsonPath("$.requiredPermissions[0]").value("SUPPORT_ACTION_REQUEST"))
                .andExpect(jsonPath("$.requiredPermissions[1]").value("SUPPORT_ORDER_CANCEL"))
                .andExpect(jsonPath("$.policyVersion").value("support-action-policy/2026-08-12/v1"))
                .andExpect(jsonPath("$.targetVersion").value(0))
        }

        @Test
        fun `stale target scope mismatch revoked verification and permission revoke are closed denials`() {
            evaluate(expectedVersion = 1)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("STALE_TARGET_VERSION"))

            jdbcTemplate.update(
                "UPDATE support_verification_session SET action_scope = 'PERSONAL_DATA_REVEAL' WHERE id = ?",
                sessionId,
            )
            evaluate(expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("VERIFICATION_SCOPE_MISMATCH"))

            jdbcTemplate.update(
                "UPDATE support_verification_session SET action_scope = 'SUPPORT_ACTION', state = 'REVOKED', revoked_at = now() WHERE id = ?",
                sessionId,
            )
            evaluate(expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("INSUFFICIENT_VERIFICATION"))

            jdbcTemplate.update(
                "UPDATE support_verification_session SET state = 'VERIFIED', revoked_at = NULL WHERE id = ?",
                sessionId,
            )
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'SUPPORT_ORDER_CANCEL'",
                actorId,
            )
            evaluate(expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("MISSING_PERMISSION"))
        }

        @Test
        fun `relationship mismatch is denied and an unlinked order is forbidden before owner lookup`() {
            jdbcTemplate.update(
                "UPDATE ordering_order SET customer_id = ? WHERE id = ?",
                UUID.randomUUID(),
                orderId,
            )
            evaluate(expectedVersion = 0)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("TARGET_RELATIONSHIP_MISMATCH"))

            jdbcTemplate.update(
                "UPDATE support_case_subject_link SET unlinked_by_actor_id = ?, unlink_reason = 'TEST', unlinked_at = now(), unlink_case_version = 1 WHERE id = ?",
                actorId,
                orderLinkId,
            )
            evaluate(expectedVersion = 0).andExpect(status().isForbidden)
        }

        private fun evaluate(expectedVersion: Long) =
            mockMvc.perform(
                post("/api/v1/support/cases/$caseId/action-evaluations")
                    .with(jwt().jwt { it.subject(actorId.toString()) })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"action":"ORDER_CANCELLATION","orderId":"$orderId","expectedTargetVersion":$expectedVersion,
                         "verificationSessionId":"$sessionId"}
                        """.trimIndent(),
                    ),
            )

        private fun seedSupportScope() {
            val now = Instant.now().minusSeconds(30)
            caseId = UUID.randomUUID()
            customerLinkId = UUID.randomUUID()
            orderLinkId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_STATUS', 'NORMAL', 'ACTION_EVALUATION', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                actorId,
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
                customerLinkId,
                caseId,
                fixture.customerId,
                actorId,
                Timestamp.from(now),
                orderLinkId,
                caseId,
                orderId,
                actorId,
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_verification_session (
                    id, support_case_id, subject_link_id, subject_type, subject_id, actor_id, purpose, action_scope,
                    requested_level, state, invalid_attempts, started_at, expires_at, verified_at, version
                ) VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'CASE_RESOLUTION', 'SUPPORT_ACTION',
                          'BASIC', 'VERIFIED', 0, ?, ?, ?, 1)
                """.trimIndent(),
                sessionId,
                caseId,
                customerLinkId,
                fixture.customerId,
                actorId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(900)),
                Timestamp.from(now.plusSeconds(1)),
            )
        }

        private fun grant(permission: String) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                actorId,
                permission,
                "support-action-evaluation:$permission:$actorId",
            )
        }
    }
