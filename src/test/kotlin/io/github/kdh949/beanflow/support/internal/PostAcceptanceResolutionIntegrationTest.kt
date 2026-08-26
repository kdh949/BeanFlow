package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.payment.internal.GatewayRefundResult
import io.github.kdh949.beanflow.payment.internal.PaymentEntity
import io.github.kdh949.beanflow.payment.internal.PaymentJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodEntity
import io.github.kdh949.beanflow.payment.internal.PaymentMethodJpaRepository
import io.github.kdh949.beanflow.payment.internal.PaymentMethodStatus
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import io.github.kdh949.beanflow.payment.internal.domain.PaymentApprovalState
import io.github.kdh949.beanflow.payment.internal.domain.PaymentType
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionOutcome
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionResponsibility
import io.github.kdh949.beanflow.support.internal.domain.SupportActionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.support-case-idempotency.retention.initial-delay-ms=3600000",
    ],
)
internal class PostAcceptanceResolutionIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbc: JdbcTemplate,
        private val payloads: PostAcceptanceResolutionPayloadCanonicalizer,
        private val service: PostAcceptanceResolutionApplicationService,
        private val payments: PaymentJpaRepository,
        private val methods: PaymentMethodJpaRepository,
        private val gateway: ScriptedTestPaymentGateway,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbc.execute("DROP TRIGGER IF EXISTS test_support_resolution_audit_fault ON operations_audit_record")
            jdbc.execute("DROP FUNCTION IF EXISTS test_support_resolution_audit_fault()")
            jdbc.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    support_case,
                    operations_operator_permission_grant,
                    operations_audit_record,
                    notification_customer_preference,
                    notification_inbox_item,
                    notification_delivery,
                    payment_refund,
                    payment_provider_request_snapshot,
                    payment_payment,
                    payment_method,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            gateway.reset()
        }

        @ParameterizedTest
        @EnumSource(PostAcceptanceState::class)
        fun `post acceptance state matrix resolves without rewriting order facts`(state: PostAcceptanceState) {
            val fixture = seed(state, PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION, cashRefundKrw = 0)
            val resolutionId = create(fixture, "create-${state.name.lowercase()}").andExpect(status().isCreated).andReturn().resolutionId()

            execute(fixture, resolutionId, "execute-${state.name.lowercase()}")
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("RESOLVED"))
                .andExpect(jsonPath("$.triggerOrderState").value(state.name))
                .andExpect(jsonPath("$.steps[?(@.type == 'CUSTOMER_NOTIFICATION')].state").value("SUCCEEDED"))

            assertThat(value("SELECT state FROM ordering_order WHERE id = ?", fixture.orderId)).isEqualTo(state.name)
            assertThat(count("notification_delivery")).isOne()
        }

        @Test
        fun `provider timeout stays reconciling and exact execution replay does not reissue refund`() {
            val fixture = seed(PostAcceptanceState.PREPARING, PostAcceptanceResolutionOutcome.FULL_REFUND)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Unknown("PROVIDER_TIMEOUT"))
            val resolutionId = create(fixture, "create-provider-timeout").andReturn().resolutionId()

            execute(fixture, resolutionId, "execute-provider-timeout")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("RECONCILING"))
                .andExpect(jsonPath("$.steps[?(@.type == 'PAYMENT_REFUND')].state").value("UNKNOWN"))

            execute(fixture, resolutionId, "execute-provider-timeout")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("RECONCILING"))

            assertThat(gateway.rejectionRefundCalls.get()).isOne()
            assertThat(count("payment_refund")).isOne()
            assertThat(count("notification_delivery")).isZero()
        }

        @Test
        fun `manual payment outcome requires explicit reconciliation and performs lookup instead of reissue`() {
            val fixture = seed(PostAcceptanceState.PREPARING, PostAcceptanceResolutionOutcome.FULL_REFUND)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Failed("REFUND_DECLINED"))
            val resolutionId = create(fixture, "create-manual-review").andReturn().resolutionId()

            execute(fixture, resolutionId, "execute-manual-review")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("MANUAL_REVIEW"))
            val version = number("SELECT version FROM support_post_acceptance_resolution WHERE id = ?", resolutionId)
            gateway.enqueueRejectionRefundLookup(GatewayRefundResult.Succeeded("provider-reconciled-refund"))

            mockMvc
                .perform(
                    post("/api/v1/support/post-acceptance-resolutions/$resolutionId/reconciliations")
                        .with(jwt().jwt { it.subject(EXECUTOR_ID.toString()) })
                        .header("Idempotency-Key", "reconcile-manual-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"stepType":"PAYMENT_REFUND","expectedResolutionVersion":$version,
                             "expectedOrderVersion":$ORDER_VERSION}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("RESOLVED"))

            assertThat(gateway.rejectionRefundCalls.get()).isOne()
            assertThat(gateway.rejectionRefundLookupCalls.get()).isOne()
            assertThat(count("payment_refund")).isOne()
        }

        @Test
        fun `undetermined responsibility remediates customer but leaves cost attribution visible`() {
            val fixture =
                seed(
                    PostAcceptanceState.READY,
                    PostAcceptanceResolutionOutcome.FULL_REFUND,
                    PostAcceptanceResolutionResponsibility.UNDETERMINED,
                )
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-resolution-refund"))
            val resolutionId = create(fixture, "create-undetermined").andReturn().resolutionId()

            execute(fixture, resolutionId, "execute-undetermined")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("PARTIALLY_RESOLVED"))
                .andExpect(jsonPath("$.steps[?(@.type == 'PAYMENT_REFUND')].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[?(@.type == 'SETTLEMENT_ADJUSTMENT')].state").value("BLOCKED"))
                .andExpect(jsonPath("$.steps[?(@.type == 'CUSTOMER_NOTIFICATION')].state").value("SUCCEEDED"))

            assertThat(count("payment_refund")).isOne()
            assertThat(count("settlement_support_resolution_adjustment")).isZero()
        }

        @Test
        fun `audit failure after owner success rolls support claim back and reconciliation consumes owner replay`() {
            val fixture = seed(PostAcceptanceState.COMPLETED, PostAcceptanceResolutionOutcome.FULL_REFUND)
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-resolution-refund"))
            val resolutionId = create(fixture, "create-audit-recovery").andReturn().resolutionId()
            installStepAuditFault()

            execute(fixture, resolutionId, "execute-audit-recovery")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(value("SELECT state FROM payment_refund WHERE support_resolution_id = ?", resolutionId)).isEqualTo("SUCCEEDED")
            assertThat(
                value(
                    "SELECT state FROM support_post_acceptance_resolution_step WHERE resolution_id = ? AND step_type = 'PAYMENT_REFUND'",
                    resolutionId,
                ),
            ).isEqualTo("PROCESSING")
            jdbc.execute("DROP TRIGGER IF EXISTS test_support_resolution_audit_fault ON operations_audit_record")
            jdbc.update(
                "UPDATE support_post_acceptance_resolution_step SET claim_until = now() - interval '1 second' " +
                    "WHERE resolution_id = ? AND step_type = 'PAYMENT_REFUND'",
                resolutionId,
            )

            execute(fixture, resolutionId, "execute-audit-recovery")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("RESOLVED"))

            assertThat(gateway.rejectionRefundCalls.get()).isOne()
            assertThat(gateway.rejectionRefundLookupCalls.get()).isZero()
            assertThat(count("payment_refund")).isOne()
        }

        @Test
        fun `create audit failure rolls back resolution and API responses never permit storage`() {
            val fixture = seed(PostAcceptanceState.PREPARING, PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION, cashRefundKrw = 0)
            installAllAuditFault()

            create(fixture, "create-audit-failure")
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(count("support_post_acceptance_resolution")).isZero()
            assertThat(value("SELECT state FROM support_action_request WHERE id = ?", fixture.requestId)).isEqualTo("READY_FOR_EXECUTION")
        }

        @Test
        fun `read endpoint is no-store and exposes terminal resolution binding`() {
            val fixture = seed(PostAcceptanceState.READY, PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION, cashRefundKrw = 0)
            val resolutionId = create(fixture, "create-read-binding").andReturn().resolutionId()
            execute(fixture, resolutionId, "execute-read-binding").andExpect(status().isOk)

            mockMvc
                .perform(
                    get("/api/v1/support/post-acceptance-resolutions/$resolutionId")
                        .with(jwt().jwt { it.subject(EXECUTOR_ID.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.resolutionId").value(resolutionId.toString()))

            assertThat(
                jdbc.queryForObject(
                    "SELECT terminal_resolution_id FROM support_action_request WHERE id = ?",
                    UUID::class.java,
                    fixture.requestId,
                ),
            ).isEqualTo(resolutionId)
        }

        @Test
        fun `concurrent exact plan creation converges on one resolution`() {
            val fixture = seed(PostAcceptanceState.PREPARING, PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION, cashRefundKrw = 0)
            val command = fixture.createCommand("concurrent-create")
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        executor.submit<PostAcceptanceResolutionResource> {
                            check(start.await(5, TimeUnit.SECONDS))
                            service.create(command)
                        }
                    }
                start.countDown()
                val resources = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertThat(resources.map { it.resolutionId }.distinct()).hasSize(1)
                assertThat(count("support_post_acceptance_resolution")).isOne()
                assertThat(count("support_post_acceptance_resolution_step")).isEqualTo(5)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `concurrent execution claims one owner effect`() {
            val fixture = seed(PostAcceptanceState.PREPARING, PostAcceptanceResolutionOutcome.FULL_REFUND)
            val resolutionId = service.create(fixture.createCommand("concurrent-plan")).resolutionId
            gateway.enqueueRejectionRefund(GatewayRefundResult.Succeeded("provider-concurrent-refund"))
            val command =
                ExecutePostAcceptanceResolutionCommand(
                    EXECUTOR_ID,
                    resolutionId,
                    0,
                    0,
                    ORDER_VERSION,
                    "concurrent-execute",
                )
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        executor.submit<PostAcceptanceResolutionResource> {
                            ready.countDown()
                            check(start.await(30, TimeUnit.SECONDS))
                            service.execute(command)
                        }
                    }
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
                start.countDown()
                futures.forEach { it.get(60, TimeUnit.SECONDS) }

                assertThat(gateway.rejectionRefundCalls.get()).isOne()
                assertThat(count("payment_refund")).isOne()
                assertThat(service.get(EXECUTOR_ID, resolutionId).state.name).isEqualTo("RESOLVED")
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }

        private fun seed(
            state: PostAcceptanceState,
            outcome: PostAcceptanceResolutionOutcome,
            responsibility: PostAcceptanceResolutionResponsibility = PostAcceptanceResolutionResponsibility.PLATFORM,
            cashRefundKrw: Long = 7_000,
        ): Fixture {
            val now = Instant.now().minusSeconds(60)
            val orderId = UUID.randomUUID()
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            insertOrder(orderId, customerId, storeId, state, now)
            insertPayment(orderId, customerId, now)
            val caseId = UUID.randomUUID()
            val requestId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val expiresAt = now.plusSeconds(900)
            insertSupportScope(caseId, sessionId, orderId, customerId, now, expiresAt)
            grantPermissions()
            val draft =
                CreatePostAcceptanceResolutionCommand(
                    EXECUTOR_ID,
                    orderId,
                    requestId,
                    1,
                    0,
                    ORDER_VERSION,
                    outcome,
                    responsibility,
                    cashRefundKrw,
                    false,
                    false,
                    null,
                    DIGEST,
                    "digest-only",
                )
            insertApprovedRequest(caseId, requestId, sessionId, orderId, cashRefundKrw, payloads.actionDigest(draft), now, expiresAt)
            return Fixture(orderId, requestId, outcome, responsibility, cashRefundKrw)
        }

        private fun insertOrder(
            orderId: UUID,
            customerId: UUID,
            storeId: UUID,
            state: PostAcceptanceState,
            now: Instant,
        ) {
            jdbc.update(
                "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                storeId,
            )
            val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbc, orderId, now)
            val readyAt = now.plusSeconds(40).takeIf { state != PostAcceptanceState.PREPARING }
            val completedAt = now.plusSeconds(50).takeIf { state == PostAcceptanceState.COMPLETED }
            jdbc.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
            try {
                jdbc.update(
                    """
                    INSERT INTO ordering_order (
                        id, customer_id, store_id, pickup_slot_id,
                        public_reference, pickup_business_date, pickup_sequence,
                        store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                        state, subtotal_krw,
                        coupon_discount_krw, points_applied_krw, payable_krw, currency,
                        reservation_expires_at, paid_at, accepted_at, preparing_at, ready_at, completed_at,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-13', ?,
                              'Test Store', ?, ?,
                              ?, 7000, 0, 0, 7000, 'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    orderId,
                    customerId,
                    storeId,
                    UUID.randomUUID(),
                    publicReference,
                    OrderCreationDatabaseFixture.pickupSequence(orderId),
                    Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(600)),
                    state.name,
                    Timestamp.from(now.plusSeconds(10)),
                    Timestamp.from(now.plusSeconds(20)),
                    Timestamp.from(now.plusSeconds(30)),
                    readyAt?.let(Timestamp::from),
                    completedAt?.let(Timestamp::from),
                    Timestamp.from(now),
                    Timestamp.from(completedAt ?: readyAt ?: now.plusSeconds(30)),
                    ORDER_VERSION,
                )
            } finally {
                jdbc.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
            }
        }

        private fun insertPayment(
            orderId: UUID,
            customerId: UUID,
            now: Instant,
        ) {
            val methodId = UUID.randomUUID()
            val paymentId = UUID.randomUUID()
            methods.saveAndFlush(
                PaymentMethodEntity(
                    id = methodId,
                    customerId = customerId,
                    provider = "SCRIPTED",
                    tokenReference = "token",
                    displayAlias = "test",
                    cardBrand = "TEST",
                    lastFour = "1234",
                    status = PaymentMethodStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            payments.saveAndFlush(
                PaymentEntity(
                    id = paymentId,
                    orderId = orderId,
                    customerId = customerId,
                    paymentMethodId = methodId,
                    type = PaymentType.EXTERNAL,
                    approvalState = PaymentApprovalState.APPROVED,
                    requestedAmountKrw = 7_000,
                    approvedAmountKrw = 7_000,
                    currency = "KRW",
                    sourceReference = "payment:$paymentId",
                    providerTransactionReference = "provider-payment-$paymentId",
                    correlationId = "correlation-$orderId",
                    approvedAt = now.plusSeconds(10),
                    createdAt = now,
                    updatedAt = now.plusSeconds(10),
                ),
            )
            jdbc.update(
                """
                INSERT INTO payment_provider_request_snapshot (
                    payment_id, payment_method_id, provider, token_reference, provider_customer_reference, created_at
                ) VALUES (?, ?, 'SCRIPTED', 'token', NULL, ?)
                """.trimIndent(),
                paymentId,
                methodId,
                Timestamp.from(now),
            )
        }

        private fun insertSupportScope(
            caseId: UUID,
            sessionId: UUID,
            orderId: UUID,
            customerId: UUID,
            now: Instant,
            expiresAt: Instant,
        ) {
            jdbc.update(
                """
                INSERT INTO support_case (
                    id, requester_type, requester_reference, category, priority, reason, state,
                    current_assignee_id, opened_at, last_changed_at, version, retention_policy_version_id
                ) VALUES (?, 'CUSTOMER', 'masked-reference', 'ORDER_CANCELLATION', 'NORMAL',
                          'POST_ACCEPTANCE_RESOLUTION', 'OPEN', ?, ?, ?, 0, 7)
                """.trimIndent(),
                caseId,
                EXECUTOR_ID,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            val customerLinkId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO support_case_subject_link (
                    id, support_case_id, subject_type, subject_id, relationship, linked_by_actor_id, reason, linked_at
                ) VALUES
                    (?, ?, 'CUSTOMER', ?, 'REQUESTER', ?, 'POST_ACCEPTANCE_RESOLUTION', ?),
                    (?, ?, 'ORDER', ?, 'RELATED_ORDER', ?, 'POST_ACCEPTANCE_RESOLUTION', ?)
                """.trimIndent(),
                customerLinkId,
                caseId,
                customerId,
                REQUESTER_ID,
                Timestamp.from(now),
                UUID.randomUUID(),
                caseId,
                orderId,
                REQUESTER_ID,
                Timestamp.from(now),
            )
            jdbc.update(
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
                customerId,
                REQUESTER_ID,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now.plusSeconds(1)),
            )
        }

        private fun insertApprovedRequest(
            caseId: UUID,
            requestId: UUID,
            sessionId: UUID,
            orderId: UUID,
            amountKrw: Long,
            actionDigest: String,
            now: Instant,
            expiresAt: Instant,
        ) {
            val revisionId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO support_action_request (
                    id, support_case_id, action, target_type, target_id, requester_actor_id, executor_actor_id,
                    current_revision_number, approval_route, state, support_approver_actor_id,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'POST_ACCEPTANCE_RESOLUTION', 'ORDER', ?, ?, ?, 1,
                          'SUPPORT_MANAGER', 'READY_FOR_EXECUTION', ?, ?, ?, 0)
                """.trimIndent(),
                requestId,
                caseId,
                orderId,
                REQUESTER_ID,
                EXECUTOR_ID,
                APPROVER_ID,
                Timestamp.from(now.plusSeconds(2)),
                Timestamp.from(now.plusSeconds(3)),
            )
            jdbc.update(
                """
                INSERT INTO support_action_revision (
                    id, request_id, revision_number, action, target_type, target_id, action_payload_digest,
                    verification_session_id, policy_version, target_version, amount_krw, reason, evidence_digest,
                    expires_at, created_by_actor_id, created_at
                ) VALUES (?, ?, 1, 'POST_ACCEPTANCE_RESOLUTION', 'ORDER', ?, ?, ?, ?, ?, ?,
                          'POST_ACCEPTANCE_RESOLUTION', ?, ?, ?, ?)
                """.trimIndent(),
                revisionId,
                requestId,
                orderId,
                actionDigest,
                sessionId,
                SupportActionPolicy.POLICY_VERSION,
                ORDER_VERSION,
                amountKrw,
                DIGEST,
                Timestamp.from(expiresAt),
                REQUESTER_ID,
                Timestamp.from(now.plusSeconds(2)),
            )
            jdbc.update(
                """
                INSERT INTO support_action_approval_step (
                    id, request_id, revision_id, revision_number, step_type, state,
                    decided_by_actor_id, decision_reason, decided_at, created_at
                ) VALUES (?, ?, ?, 1, 'SUPPORT_MANAGER', 'APPROVED', ?,
                          'POST_ACCEPTANCE_RESOLUTION', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                requestId,
                revisionId,
                APPROVER_ID,
                Timestamp.from(now.plusSeconds(3)),
                Timestamp.from(now.plusSeconds(3)),
            )
        }

        private fun grantPermissions() {
            mapOf(
                REQUESTER_ID to listOf("SUPPORT_ACTION_REQUEST", "SUPPORT_RESOLUTION_REQUEST"),
                EXECUTOR_ID to listOf("SUPPORT_CASE_READ", "SUPPORT_ACTION_EXECUTE", "SUPPORT_RESOLUTION_EXECUTE"),
            ).forEach { (actorId, permissions) ->
                permissions.forEach { permission ->
                    jdbc.update(
                        """
                        INSERT INTO operations_operator_permission_grant (
                            actor_id, permission, state, granted_at, version, audit_source_reference
                        ) VALUES (?, ?, 'ACTIVE', now(), 1, ?)
                        """.trimIndent(),
                        actorId,
                        permission,
                        "support-resolution:$permission:${UUID.randomUUID()}",
                    )
                }
            }
        }

        private fun create(
            fixture: Fixture,
            key: String,
        ) = mockMvc
            .perform(
                post("/api/v1/support/orders/${fixture.orderId}/post-acceptance-resolutions")
                    .with(jwt().jwt { it.subject(EXECUTOR_ID.toString()) })
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"requestId":"${fixture.requestId}","revisionNumber":1,"expectedRequestVersion":0,
                         "expectedOrderVersion":$ORDER_VERSION,"outcome":"${fixture.outcome}",
                         "responsibility":"${fixture.responsibility}","cashRefundKrw":${fixture.cashRefundKrw},
                         "restorePoints":false,"restoreCoupon":false,"evidenceDigest":"$DIGEST"}
                        """.trimIndent(),
                    ),
            ).andExpect(header().string("Cache-Control", containsString("no-store")))

        private fun execute(
            fixture: Fixture,
            resolutionId: UUID,
            key: String,
        ) = mockMvc.perform(
            post("/api/v1/support/post-acceptance-resolutions/$resolutionId/executions")
                .with(jwt().jwt { it.subject(EXECUTOR_ID.toString()) })
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedResolutionVersion":0,"expectedRequestVersion":0,
                     "expectedOrderVersion":$ORDER_VERSION}
                    """.trimIndent(),
                ),
        )

        private fun Fixture.createCommand(key: String) =
            CreatePostAcceptanceResolutionCommand(
                EXECUTOR_ID,
                orderId,
                requestId,
                1,
                0,
                ORDER_VERSION,
                outcome,
                responsibility,
                cashRefundKrw,
                false,
                false,
                null,
                DIGEST,
                key,
            )

        private fun installStepAuditFault() = installAuditFault("NEW.action = 'SUPPORT_RESOLUTION_STEP_RECORDED'")

        private fun installAllAuditFault() = installAuditFault("TRUE")

        private fun installAuditFault(condition: String) {
            jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION test_support_resolution_audit_fault()
                RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    IF $condition THEN
                        RAISE EXCEPTION 'injected support resolution audit failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbc.execute(
                """
                CREATE TRIGGER test_support_resolution_audit_fault
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION test_support_resolution_audit_fault()
                """.trimIndent(),
            )
        }

        private fun MvcResult.resolutionId(): UUID =
            UUID.fromString(requireNotNull(Regex("\\\"resolutionId\\\":\\\"([^\\\"]+)\\\"").find(response.contentAsString)).groupValues[1])

        private fun count(table: String): Long = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun value(
            sql: String,
            vararg args: Any,
        ): String = requireNotNull(jdbc.queryForObject(sql, String::class.java, *args))

        private fun number(
            sql: String,
            vararg args: Any,
        ): Long = requireNotNull(jdbc.queryForObject(sql, Long::class.java, *args))

        private data class Fixture(
            val orderId: UUID,
            val requestId: UUID,
            val outcome: PostAcceptanceResolutionOutcome,
            val responsibility: PostAcceptanceResolutionResponsibility,
            val cashRefundKrw: Long,
        )

        internal enum class PostAcceptanceState {
            PREPARING,
            READY,
            COMPLETED,
        }

        private companion object {
            val REQUESTER_ID: UUID = UUID.fromString("81000000-0000-0000-0000-000000000001")
            val APPROVER_ID: UUID = UUID.fromString("81000000-0000-0000-0000-000000000002")
            val EXECUTOR_ID: UUID = UUID.fromString("81000000-0000-0000-0000-000000000003")
            const val ORDER_VERSION = 4L
            const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    }
