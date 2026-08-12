package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorization
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class SupportOrderChangeExecutionIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val createOrder: CreateOrderUseCase,
        private val payloads: SupportOrderChangePayloadCanonicalizer,
    ) {
        private val supportActorId = UUID.fromString("67000000-0000-0000-0000-000000000001")
        private val storeActorId = UUID.fromString("67000000-0000-0000-0000-000000000002")
        private lateinit var fixture: OrderCreationFixture
        private lateinit var orderId: UUID
        private lateinit var caseId: UUID
        private lateinit var sessionId: UUID
        private lateinit var requestId: UUID
        private lateinit var revisionId: UUID
        private lateinit var expiresAt: Instant
        private var orderVersion: Long = 0

        @BeforeEach
        fun resetAndSeedPendingCancellation() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_support_order_change_audit_fault ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_support_order_change_audit_fault()")
            jdbcTemplate.execute("TRUNCATE TABLE support_order_change_authorization CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE support_case CASCADE")
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.execute("TRUNCATE TABLE operations_operator_permission_grant")
            fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val created = createOrder.create("support-order-change-order", fixture.command())
            assertThat(created.status).isEqualTo(201)
            orderId = UUID.fromString(requireNotNull(Regex("\\\"orderId\\\":\\\"([^\\\"]+)\\\"").find(created.body)).groupValues[1])
            orderVersion = number("SELECT version FROM ordering_order WHERE id = ?", orderId)
            seedSupportScope()
            grantExecutionPermissions()
            insertActionRequest(SupportActionType.ORDER_CANCELLATION, cancellationDigest())
        }

        @Test
        fun `pending cancellation commits owner and support outcome and exact replay is no-store`() {
            val first =
                executeCancellation("execute-pending-001")
                    .andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.outcome").value("EXECUTED"))
                    .andExpect(jsonPath("$.previousTargetState").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.currentTargetState").value("CANCELLED"))
                    .andExpect(jsonPath("$.paymentRecoveryState").value("NOT_REQUIRED"))
                    .andExpect(jsonPath("$.requestState").value("EXECUTED"))
                    .andReturn()
            val executionId = executionId(first)

            executeCancellation("execute-pending-001")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.requestVersion").value(1))

            executeCancellation("execute-pending-001", CustomerCancellationReasonCode.OTHER)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("CANCELLED")
            assertThat(value("SELECT cancellation_cause FROM ordering_order WHERE id = ?", orderId)).isEqualTo("SUPPORT_REQUEST")
            assertThat(value("SELECT state FROM fulfillment_pickup_reservation WHERE order_id = ?", orderId)).isEqualTo("RELEASED")
            assertThat(count("support_order_change_execution")).isOne()
            assertThat(count("ordering_support_order_change_history")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT terminal_execution_id FROM support_action_request WHERE id = ?",
                    UUID::class.java,
                    requestId,
                ),
            ).isEqualTo(executionId)
        }

        @Test
        fun `revoked execute permission rejects before owner mutation`() {
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() " +
                    "WHERE actor_id = ? AND permission = 'SUPPORT_ACTION_EXECUTE'",
                supportActorId,
            )

            executeCancellation("execute-after-revoke")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(count("support_order_change_execution")).isZero()
        }

        @Test
        fun `audit insert failure rolls back order execution and terminal request`() {
            installAuditFault()
            try {
                executeCancellation("execute-audit-failure")
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            } finally {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_support_order_change_audit_fault ON operations_audit_record")
            }

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT")
            assertThat(value("SELECT state FROM support_action_request WHERE id = ?", requestId)).isEqualTo("READY_FOR_EXECUTION")
            assertThat(count("support_order_change_execution")).isZero()
            assertThat(count("ordering_support_order_change_history")).isZero()
        }

        @Test
        fun `accepted reschedule requires exact store confirmation and consumes it once`() {
            val newSlotId = UUID.randomUUID()
            makeAccepted(newSlotId)
            resetRequest(SupportActionType.PICKUP_RESCHEDULE, pickupDigest(newSlotId))
            insertStoreMembership()

            executeReschedule("execute-without-confirmation", newSlotId, null)
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("SUPPORT_ORDER_CHANGE_AUTHORIZATION_REQUIRED"))

            val authorization =
                createConfirmation("confirmation-001")
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.authorizationType").value("CONFIRMATION"))
                    .andExpect(jsonPath("$.maxSuccessfulUses").value(1))
                    .andExpect(jsonPath("$.successfulUses").value(0))
                    .andReturn()
            val authorizationId = authorizationId(authorization)

            val first =
                executeReschedule("execute-accepted-reschedule", newSlotId, authorizationId)
                    .andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.outcome").value("EXECUTED"))
                    .andExpect(jsonPath("$.previousTargetState").value("ACCEPTED"))
                    .andExpect(jsonPath("$.currentPickupSlotId").value(newSlotId.toString()))
                    .andExpect(jsonPath("$.authorizationId").value(authorizationId.toString()))
                    .andReturn()

            executeReschedule("execute-accepted-reschedule", newSlotId, authorizationId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.executionId").value(executionId(first).toString()))

            assertThat(count("support_order_change_authorization_use")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT state FROM notification_delivery WHERE order_id = ? AND template = 'SUPPORT_PICKUP_RESCHEDULED'",
                    String::class.java,
                    orderId,
                ),
            ).isEqualTo("PENDING")
            assertThat(
                number(
                    "SELECT confirmed_count FROM fulfillment_pickup_slot WHERE id = ?",
                    newSlotId,
                ),
            ).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT pickup_slot_id FROM ordering_order WHERE id = ?",
                    UUID::class.java,
                    orderId,
                ),
            ).isEqualTo(newSlotId)
        }

        @Test
        fun `preparing winner becomes durable resolution required without authorization use`() {
            val newSlotId = UUID.randomUUID()
            makeAccepted(newSlotId)
            resetRequest(SupportActionType.PICKUP_RESCHEDULE, pickupDigest(newSlotId))
            insertStoreMembership()
            val authorizationId = authorizationId(createConfirmation("preparing-confirmation").andReturn())
            jdbcTemplate.update(
                "UPDATE ordering_order SET state = 'PREPARING', preparing_at = accepted_at + interval '1 second', " +
                    "updated_at = accepted_at + interval '1 second', version = version + 1 WHERE id = ?",
                orderId,
            )

            executeReschedule("execute-preparing-race", newSlotId, authorizationId)
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.outcome").value("RESOLUTION_REQUIRED"))
                .andExpect(jsonPath("$.currentTargetState").value("PREPARING"))
                .andExpect(jsonPath("$.requestState").value("RESOLUTION_REQUIRED"))

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", orderId)).isEqualTo("PREPARING")
            assertThat(count("fulfillment_pickup_reschedule_history")).isZero()
            assertThat(count("support_order_change_authorization_use")).isZero()
        }

        @Test
        fun `store cancellation delegation has one-use policy and exact creation replay`() {
            insertStoreMembership()

            val first =
                createDelegation("delegation-cancel-001", SupportActionType.ORDER_CANCELLATION)
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.authorizationType").value("DELEGATION"))
                    .andExpect(jsonPath("$.action").value("ORDER_CANCELLATION"))
                    .andExpect(jsonPath("$.maxSuccessfulUses").value(1))
                    .andExpect(jsonPath("$.successfulUses").value(0))
                    .andExpect(jsonPath("$.requestId").isEmpty)
                    .andReturn()

            createDelegation("delegation-cancel-001", SupportActionType.ORDER_CANCELLATION)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.authorizationId").value(authorizationId(first).toString()))

            createDelegation("delegation-cancel-001", SupportActionType.PICKUP_RESCHEDULE)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        }

        private fun seedSupportScope() {
            val now = Instant.now().minusSeconds(30)
            expiresAt = now.plusSeconds(900)
            caseId = UUID.randomUUID()
            sessionId = UUID.randomUUID()
            val customerLinkId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_CANCELLATION', 'NORMAL', 'ORDER_CHANGE', 'OPEN',
                          ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                supportActorId,
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
                supportActorId,
                supportActorId,
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES
                    (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'ORDER_CHANGE', ?),
                    (?, ?, 'ORDER', ?, 'RELATED_ORDER', ?, 'ORDER_CHANGE', ?)
                """.trimIndent(),
                customerLinkId,
                caseId,
                fixture.customerId,
                supportActorId,
                Timestamp.from(now),
                UUID.randomUUID(),
                caseId,
                orderId,
                supportActorId,
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
                supportActorId,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now.plusSeconds(1)),
            )
        }

        private fun insertActionRequest(
            action: SupportActionType,
            digest: String,
        ) {
            requestId = UUID.randomUUID()
            revisionId = UUID.randomUUID()
            val now = Instant.now().minusSeconds(5)
            jdbcTemplate.update(
                """
                INSERT INTO support_action_request (
                    id, support_case_id, action, target_type, target_id, requester_actor_id, executor_actor_id,
                    current_revision_number, approval_route, state, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'ORDER', ?, ?, ?, 1, 'NONE', 'READY_FOR_EXECUTION', ?, ?, 0)
                """.trimIndent(),
                requestId,
                caseId,
                action.name,
                orderId,
                supportActorId,
                supportActorId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            jdbcTemplate.update(
                """
                INSERT INTO support_action_revision (
                    id, request_id, revision_number, action, target_type, target_id, action_payload_digest,
                    verification_session_id, policy_version, target_version, reason, evidence_digest,
                    expires_at, created_by_actor_id, created_at
                ) VALUES (?, ?, 1, ?, 'ORDER', ?, ?, ?, ?, ?, 'ORDER_CHANGE', ?, ?, ?, ?)
                """.trimIndent(),
                revisionId,
                requestId,
                action.name,
                orderId,
                digest,
                sessionId,
                SupportActionPolicy.POLICY_VERSION,
                orderVersion,
                EVIDENCE_DIGEST,
                Timestamp.from(expiresAt),
                supportActorId,
                Timestamp.from(now),
            )
        }

        private fun resetRequest(
            action: SupportActionType,
            digest: String,
        ) {
            jdbcTemplate.execute("TRUNCATE TABLE support_order_change_authorization CASCADE")
            jdbcTemplate.execute("TRUNCATE TABLE support_action_request CASCADE")
            insertActionRequest(action, digest)
            grantExecutionPermissions()
        }

        private fun cancellationDigest(): String =
            payloads.actionDigest(
                ExecuteSupportOrderChangeCommand(
                    supportActorId,
                    UUID.randomUUID(),
                    SupportActionType.ORDER_CANCELLATION,
                    1,
                    0,
                    orderVersion,
                    CustomerCancellationReasonCode.CHANGED_MIND,
                    null,
                    null,
                    "digest-only",
                ),
                orderId,
            )

        private fun pickupDigest(newSlotId: UUID): String =
            payloads.actionDigest(
                ExecuteSupportOrderChangeCommand(
                    supportActorId,
                    UUID.randomUUID(),
                    SupportActionType.PICKUP_RESCHEDULE,
                    1,
                    0,
                    orderVersion,
                    null,
                    newSlotId,
                    null,
                    "digest-only",
                ),
                orderId,
            )

        private fun executeCancellation(
            key: String,
            reasonCode: CustomerCancellationReasonCode = CustomerCancellationReasonCode.CHANGED_MIND,
        ) = mockMvc.perform(
            post("/api/v1/support/action-requests/$requestId/executions")
                .with(jwt().jwt { it.subject(supportActorId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"action":"ORDER_CANCELLATION","revisionNumber":1,"expectedRequestVersion":0,
                     "expectedTargetVersion":$orderVersion,"reasonCode":"$reasonCode"}
                    """.trimIndent(),
                ),
        )

        private fun executeReschedule(
            key: String,
            newSlotId: UUID,
            authorizationId: UUID?,
        ) = mockMvc.perform(
            post("/api/v1/support/action-requests/$requestId/executions")
                .with(jwt().jwt { it.subject(supportActorId.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"action":"PICKUP_RESCHEDULE","revisionNumber":1,"expectedRequestVersion":0,
                     "expectedTargetVersion":$orderVersion,"newPickupSlotId":"$newSlotId"${
                        authorizationId?.let { ",\"authorizationId\":\"$it\"" }.orEmpty()
                    }}
                    """.trimIndent(),
                ),
        )

        private fun createConfirmation(key: String) =
            mockMvc.perform(
                post("/api/v1/stores/${fixture.storeId}/support-order-change-authorizations")
                    .with(
                        jwt()
                            .jwt {
                                it
                                    .subject(storeActorId.toString())
                                    .claim("roles", listOf("STORE_STAFF"))
                            }.authorities(SimpleGrantedAuthority("ROLE_STORE_STAFF")),
                    ).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"authorizationType":"CONFIRMATION","action":"PICKUP_RESCHEDULE",
                         "policyVersion":"${SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION}",
                         "requestId":"$requestId","revisionNumber":1,"expectedRequestVersion":0,
                         "costResponsibility":"STORE"}
                        """.trimIndent(),
                    ),
            )

        private fun createDelegation(
            key: String,
            action: SupportActionType,
        ) = mockMvc.perform(
            post("/api/v1/stores/${fixture.storeId}/support-order-change-authorizations")
                .with(
                    jwt()
                        .jwt {
                            it
                                .subject(storeActorId.toString())
                                .claim("roles", listOf("STORE_STAFF"))
                        }.authorities(SimpleGrantedAuthority("ROLE_STORE_STAFF")),
                ).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"authorizationType":"DELEGATION","action":"$action",
                     "policyVersion":"${SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION}",
                     "costResponsibility":"STORE"}
                    """.trimIndent(),
                ),
        )

        private fun makeAccepted(newSlotId: UUID) {
            val now = Instant.now().minusSeconds(30)
            jdbcTemplate.update(
                """
                INSERT INTO fulfillment_pickup_slot (
                    id, store_id, starts_at, ends_at, capacity, reserved_count, confirmed_count, version
                ) VALUES (?, ?, ?, ?, 5, 0, 0, 0)
                """.trimIndent(),
                newSlotId,
                fixture.storeId,
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(4200)),
            )
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET state = 'ACCEPTED', reservation_expires_at = NULL,
                       paid_at = created_at + interval '1 second',
                       acceptance_warning_at = created_at + interval '121 seconds',
                       acceptance_deadline_at = created_at + interval '181 seconds',
                       accepted_at = created_at + interval '120 seconds',
                       updated_at = created_at + interval '120 seconds', version = 2
                 WHERE id = ?
                """.trimIndent(),
                orderId,
            )
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_reservation SET state = 'CONFIRMED', version = version + 1 WHERE order_id = ?",
                orderId,
            )
            jdbcTemplate.update(
                "UPDATE fulfillment_pickup_slot SET reserved_count = 0, confirmed_count = 1 WHERE id = ?",
                fixture.pickupSlotId,
            )
            orderVersion = 2
        }

        private fun insertStoreMembership() {
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'STAFF', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                storeActorId,
                fixture.storeId,
                now,
                now,
            )
        }

        private fun grantExecutionPermissions() {
            listOf(
                "SUPPORT_CASE_READ",
                "SUPPORT_ORDER_READ",
                "SUPPORT_ACTION_REQUEST",
                "SUPPORT_ACTION_EXECUTE",
                "SUPPORT_ORDER_CANCEL",
                "SUPPORT_PICKUP_RESCHEDULE",
            ).forEach { permission ->
                jdbcTemplate.update(
                    """
                    INSERT INTO operations_operator_permission_grant (
                        actor_id, permission, state, granted_at, version, audit_source_reference
                    ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                    ON CONFLICT (actor_id, permission) DO UPDATE
                        SET state = 'ACTIVE', revoked_at = NULL, version = operations_operator_permission_grant.version + 1,
                            audit_source_reference = EXCLUDED.audit_source_reference
                    """.trimIndent(),
                    supportActorId,
                    permission,
                    "support-order-change:$permission:${UUID.randomUUID()}",
                )
            }
        }

        private fun installAuditFault() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_support_order_change_audit_fault()
                RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'injected support order change audit failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_support_order_change_audit_fault
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION test_support_order_change_audit_fault()
                """.trimIndent(),
            )
        }

        private fun executionId(result: MvcResult): UUID =
            UUID.fromString(
                requireNotNull(Regex("\\\"executionId\\\":\\\"([^\\\"]+)\\\"").find(result.response.contentAsString)).groupValues[1],
            )

        private fun authorizationId(result: MvcResult): UUID =
            UUID.fromString(
                requireNotNull(Regex("\\\"authorizationId\\\":\\\"([^\\\"]+)\\\"").find(result.response.contentAsString)).groupValues[1],
            )

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun number(
            sql: String,
            vararg args: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *args))

        private fun value(
            sql: String,
            vararg args: Any,
        ): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

        private companion object {
            const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        }
    }
