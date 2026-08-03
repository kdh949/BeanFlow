package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.ordering.internal.OrderCreationFixture
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import io.github.kdh949.beanflow.payment.internal.ScriptedTestPaymentGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.payment-cancellation-setup.initial-delay-ms=3600000",
        "beanflow.payment-setup-repair-maintenance.initial-delay-ms=3600000",
    ],
)
internal class PaymentSetupRepairIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val paymentGateway: ScriptedTestPaymentGateway,
        private val setupIntegrityWorker: PaymentCancellationSetupIntegrityWorker,
        private val maintenanceWorker: PaymentSetupRepairMaintenanceWorker,
    ) {
        private val proposer = UUID.fromString("20000000-0000-0000-0000-000000000052")
        private val approver = UUID.fromString("20000000-0000-0000-0000-000000000053")
        private val reviewer = UUID.fromString("20000000-0000-0000-0000-000000000054")

        @BeforeEach
        fun cleanDatabase() {
            dropAuditFailureTrigger()
            awaitPublicationsSettled()
            OrderCreationDatabaseFixture.clean(jdbcTemplate)
            jdbcTemplate.update(
                "DELETE FROM operations_operator_permission_grant WHERE permission = 'PAYMENT_CANCELLATION_SETUP_REPAIR'",
            )
            grant(proposer)
            grant(approver)
            grant(reviewer)
            paymentGateway.reset()
        }

        @AfterEach
        fun cleanupFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `different active operators restore the exact Refund for LOOKUP with atomic evidence and no provider call`() {
            val damaged = createMissingRefundCase("repair-success")

            val first = propose(damaged.caseId, proposer, "repair-propose-0001", "Verified missing refund")
            val replay = propose(damaged.caseId, proposer, "repair-propose-0001", "Verified missing refund")
            assertThat(first.status).isEqualTo(201)
            assertThat(replay.status).isEqualTo(201)
            assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
            assertThat(first.contentAsString)
                .doesNotContain(damaged.providerKey, damaged.sourceReference, damaged.orderId.toString())

            decide(damaged.proposalId(), proposer, "repair-self-0001", "Self approval must fail", "APPROVE")
                .also { response ->
                    assertThat(response.status).isEqualTo(409)
                    assertThat(response.contentAsString).contains("REPROCESSING_APPROVER_MUST_DIFFER")
                }

            val approved =
                decide(damaged.proposalId(), approver, "repair-approve-0001", "Second operator approval", "APPROVE")
            assertThat(approved.status).isEqualTo(200)
            assertThat(approved.contentAsString).contains("\"state\":\"EXECUTED\"")
            val replayedApproval =
                decide(damaged.proposalId(), approver, "repair-approve-0001", "Second operator approval", "APPROVE")
            assertThat(replayedApproval.status).isEqualTo(200)
            assertThat(replayedApproval.contentAsString).isEqualTo(approved.contentAsString)

            val refund =
                jdbcTemplate.queryForMap(
                    "SELECT id, requested_amount_krw, reason, state, provider_idempotency_key, " +
                        "source_reference, request_attempt_count, lookup_attempt_count, next_action " +
                        "FROM payment_refund WHERE order_id = ?",
                    damaged.orderId,
                )
            assertThat(refund["id"]).isEqualTo(damaged.refundId)
            assertThat(refund["requested_amount_krw"]).isEqualTo(1_000L)
            assertThat(refund["reason"]).isEqualTo("CUSTOMER_ORDER_CANCELLED")
            assertThat(refund["state"]).isEqualTo("RECONCILING")
            assertThat(refund["provider_idempotency_key"]).isEqualTo(damaged.providerKey)
            assertThat(refund["source_reference"]).isEqualTo(damaged.sourceReference)
            assertThat(refund["request_attempt_count"]).isEqualTo(0)
            assertThat(refund["lookup_attempt_count"]).isEqualTo(0)
            assertThat(refund["next_action"]).isEqualTo("LOOKUP")
            assertThat(value("SELECT status FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                .isEqualTo("RESOLVED")
            assertThat(value("SELECT resolution FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                .isEqualTo("MISSING_REFUND_RECREATED_LOOKUP_REQUIRED")
            assertThat(
                value(
                    "SELECT step.state FROM operations_order_compensation_step step " +
                        "JOIN operations_order_compensation_case bean_case ON bean_case.id = step.case_id " +
                        "WHERE bean_case.order_id = ? AND step.step_type = 'PAYMENT'",
                    damaged.orderId,
                ),
            ).isEqualTo("UNKNOWN")
            assertThat(
                jdbcTemplate.queryForList(
                    "SELECT action FROM operations_audit_record WHERE source_reference = ? ORDER BY action",
                    String::class.java,
                    "payment-setup-repair:${damaged.proposalId()}",
                ),
            ).containsExactlyInAnyOrder(
                "PAYMENT_CANCELLATION_REPAIR_PROPOSED",
                "PAYMENT_CANCELLATION_REPAIR_APPROVED_AND_EXECUTED",
                "PAYMENT_CANCELLATION_MISSING_REFUND_RECREATED",
            )
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(paymentGateway.rejectionRefundLookupCalls.get()).isZero()
        }

        @Test
        fun `snapshot change makes approval STALE and commits no financial mutation`() {
            val damaged = createMissingRefundCase("repair-stale")
            propose(damaged.caseId, proposer, "repair-propose-0002", "Verified before change")
                .also { assertThat(it.status).isEqualTo(201) }
            jdbcTemplate.update(
                "UPDATE payment_cancellation_recovery_snapshot SET version = version + 1 WHERE order_id = ?",
                damaged.orderId,
            )

            val response =
                decide(damaged.proposalId(), approver, "repair-approve-0002", "Approve stale proposal", "APPROVE")

            assertThat(response.status).isEqualTo(409)
            assertThat(response.contentAsString).contains("REPROCESSING_PROPOSAL_STALE")
            assertThat(value("SELECT state FROM operations_payment_setup_repair_proposal WHERE id = ?", damaged.proposalId()))
                .isEqualTo("STALE")
            assertThat(count("payment_refund")).isZero()
            assertThat(value("SELECT status FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                .isEqualTo("OPEN")
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(paymentGateway.rejectionRefundLookupCalls.get()).isZero()
        }

        @Test
        fun `maintenance expires pending proposals as SYSTEM and purges due idempotency in bounded work`() {
            val damaged = createMissingRefundCase("repair-expiry")
            propose(damaged.caseId, proposer, "repair-propose-0003", "Pending review")
                .also { assertThat(it.status).isEqualTo(201) }
            val proposalId = damaged.proposalId()
            jdbcTemplate.update(
                "UPDATE operations_payment_setup_repair_proposal " +
                    "SET created_at = created_at - interval '31 minutes', " +
                    "expires_at = expires_at - interval '31 minutes' WHERE id = ?",
                proposalId,
            )
            jdbcTemplate.update(
                "UPDATE operations_payment_setup_repair_idempotency " +
                    "SET created_at = created_at - interval '91 days', " +
                    "retention_expires_at = retention_expires_at - interval '91 days' WHERE proposal_id = ?",
                proposalId,
            )

            maintenanceWorker.runScheduled()

            val proposal =
                jdbcTemplate.queryForMap(
                    "SELECT state, decided_by, decision_reason, decided_at FROM " +
                        "operations_payment_setup_repair_proposal WHERE id = ?",
                    proposalId,
                )
            assertThat(proposal["state"]).isEqualTo("EXPIRED")
            assertThat(proposal["decided_by"]).isNull()
            assertThat(proposal["decision_reason"]).isEqualTo("PROPOSAL_TTL_EXPIRED")
            assertThat(proposal["decided_at"]).isNotNull()
            assertThat(count("operations_payment_setup_repair_idempotency")).isZero()
            assertThat(count("payment_refund")).isZero()
            val audit =
                jdbcTemplate.queryForMap(
                    "SELECT actor_id, actor_type, action, reason FROM operations_audit_record " +
                        "WHERE action = 'PAYMENT_CANCELLATION_REPAIR_EXPIRED' AND target_id = ?",
                    proposalId,
                )
            assertThat(audit["actor_id"]).isEqualTo("SYSTEM")
            assertThat(audit["actor_type"]).isEqualTo("SYSTEM")
            assertThat(audit["reason"]).isEqualTo("PROPOSAL_TTL_EXPIRED")
        }

        @Test
        fun `concurrent approve and reject produce one terminal proposal and no partial repair`() {
            val damaged = createMissingRefundCase("repair-race")
            propose(damaged.caseId, proposer, "repair-propose-0004", "Concurrent decision review")
                .also { assertThat(it.status).isEqualTo(201) }
            val proposalId = damaged.proposalId()
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val approval =
                    executor.submit<org.springframework.mock.web.MockHttpServletResponse> {
                        barrier.await()
                        decide(proposalId, approver, "repair-approve-0004", "Approve race", "APPROVE")
                    }
                val rejection =
                    executor.submit<org.springframework.mock.web.MockHttpServletResponse> {
                        barrier.await()
                        decide(proposalId, reviewer, "repair-reject-0004", "Reject race", "REJECT")
                    }
                val responses =
                    listOf(
                        approval.get(20, java.util.concurrent.TimeUnit.SECONDS),
                        rejection.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    )
                assertThat(responses.map { it.status }).containsExactlyInAnyOrder(200, 409)
            } finally {
                executor.shutdownNow()
            }

            val state = value("SELECT state FROM operations_payment_setup_repair_proposal WHERE id = ?", proposalId)
            assertThat(state).isIn("EXECUTED", "REJECTED")
            if (state == "EXECUTED") {
                assertThat(count("payment_refund")).isEqualTo(1)
                assertThat(value("SELECT status FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                    .isEqualTo("RESOLVED")
            } else {
                assertThat(count("payment_refund")).isZero()
                assertThat(value("SELECT status FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                    .isEqualTo("OPEN")
            }
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(paymentGateway.rejectionRefundLookupCalls.get()).isZero()
        }

        @Test
        fun `approval audit failure rolls back Refund step case proposal and decision idempotency`() {
            val damaged = createMissingRefundCase("repair-rollback")
            propose(damaged.caseId, proposer, "repair-propose-0005", "Rollback verification")
                .also { assertThat(it.status).isEqualTo(201) }
            val proposalId = damaged.proposalId()
            installAuditFailureTrigger()

            val response =
                decide(proposalId, approver, "repair-approve-0005", "Approval must be audited", "APPROVE")

            assertThat(response.status).isEqualTo(503)
            assertThat(response.contentAsString).contains("DEPENDENCY_UNAVAILABLE")
            assertThat(count("payment_refund")).isZero()
            assertThat(value("SELECT state FROM operations_payment_setup_repair_proposal WHERE id = ?", proposalId))
                .isEqualTo("PENDING_APPROVAL")
            assertThat(value("SELECT status FROM operations_reprocessing_case WHERE id = ?", damaged.caseId))
                .isEqualTo("OPEN")
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM operations_payment_setup_repair_idempotency " +
                        "WHERE operation = 'DECIDE' AND proposal_id = ?",
                    Long::class.java,
                    proposalId,
                ),
            ).isZero()
            assertThat(paymentGateway.rejectionRefundCalls.get()).isZero()
            assertThat(paymentGateway.rejectionRefundLookupCalls.get()).isZero()
        }

        private fun createMissingRefundCase(key: String): DamagedSetup {
            val fixture = OrderCreationFixture()
            OrderCreationDatabaseFixture.insertBase(jdbcTemplate, fixture)
            val orderId = createOrder(fixture, "$key-create")
            approvePayment(orderId, fixture.customerId)
            cancel(orderId, fixture.customerId, "$key-cancel")
            awaitPublicationsSettled()
            val snapshot =
                jdbcTemplate.queryForMap(
                    "SELECT cancellation_refund_id, refund_source_reference, provider_idempotency_key " +
                        "FROM payment_cancellation_recovery_snapshot WHERE order_id = ?",
                    orderId,
                )
            val refundId = snapshot["cancellation_refund_id"] as UUID
            jdbcTemplate.update("DELETE FROM payment_refund WHERE id = ?", refundId)
            setupIntegrityWorker.runScheduled()
            val caseId =
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM operations_reprocessing_case " +
                            "WHERE case_type = 'PAYMENT_CANCELLATION_SETUP' AND owner_reference LIKE ?",
                        UUID::class.java,
                        "order:$orderId:%",
                    ),
                )
            return DamagedSetup(
                orderId = orderId,
                caseId = caseId,
                refundId = refundId,
                sourceReference = snapshot["refund_source_reference"] as String,
                providerKey = snapshot["provider_idempotency_key"] as String,
            )
        }

        private fun createOrder(
            fixture: OrderCreationFixture,
            key: String,
        ): UUID {
            mockMvc
                .perform(
                    post("/api/v1/orders")
                        .with(customerJwt(fixture.customerId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "storeId":"${fixture.storeId}",
                              "pickupSlotId":"${fixture.pickupSlotId}",
                              "lines":[{"menuId":"${fixture.menuId}","optionIds":[],"quantity":1}],
                              "pointsToUseKrw":0
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
            return requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT order_id FROM ordering_idempotency_record WHERE actor_id = ? AND idempotency_key = ?",
                    UUID::class.java,
                    fixture.customerId,
                    key,
                ),
            )
        }

        private fun approvePayment(
            orderId: UUID,
            customerId: UUID,
        ) {
            val paymentMethodId = UUID.randomUUID()
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                "INSERT INTO payment_method (id, customer_id, provider, token_reference, display_alias, card_brand, " +
                    "last_four, status, created_at, updated_at, version) " +
                    "VALUES (?, ?, 'SCRIPTED', ?, 'Repair test', 'TEST', '4242', 'ACTIVE', ?, ?, 0)",
                paymentMethodId,
                customerId,
                "token:$paymentMethodId",
                now,
                now,
            )
            paymentGateway.enqueueApproval(ProviderPaymentResult.Approved("provider:$orderId", 1_000, "KRW"))
            mockMvc
                .perform(
                    post("/api/v1/orders/{orderId}/payment-confirmations", orderId)
                        .with(customerJwt(customerId))
                        .header("Idempotency-Key", "payment-$orderId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"paymentMethodId":"$paymentMethodId"}"""),
                ).andExpect(status().isOk)
        }

        private fun cancel(
            orderId: UUID,
            customerId: UUID,
            key: String,
        ) {
            mockMvc
                .perform(
                    post("/api/v1/orders/{orderId}/cancellations", orderId)
                        .with(customerJwt(customerId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reasonCode":"CHANGED_MIND"}"""),
                ).andExpect(status().isAccepted)
        }

        private fun propose(
            caseId: UUID,
            actorId: UUID,
            key: String,
            reason: String,
        ) = mockMvc
            .perform(
                post("/api/v1/operations/reprocessing-cases/{caseId}/repair-proposals", caseId)
                    .with(operatorJwt(actorId))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"$reason"}"""),
            ).andReturn()
            .response

        private fun decide(
            proposalId: UUID,
            actorId: UUID,
            key: String,
            reason: String,
            decision: String,
        ) = mockMvc
            .perform(
                post("/api/v1/operations/reprocessing-repair-proposals/{proposalId}/decisions", proposalId)
                    .with(operatorJwt(actorId))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"decision":"$decision","reason":"$reason"}"""),
            ).andReturn()
            .response

        private fun grant(actorId: UUID) {
            jdbcTemplate.update(
                "INSERT INTO operations_operator_permission_grant " +
                    "(actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference) " +
                    "VALUES (?, 'PAYMENT_CANCELLATION_SETUP_REPAIR', 'ACTIVE', now(), null, 1, ?)",
                actorId,
                "repair-test:$actorId",
            )
        }

        private fun customerJwt(actorId: UUID) =
            jwt().jwt { it.subject(actorId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun operatorJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun DamagedSetup.proposalId(): UUID =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT id FROM operations_payment_setup_repair_proposal WHERE case_id = ?",
                    UUID::class.java,
                    caseId,
                ),
            )

        private fun value(
            sql: String,
            vararg args: Any,
        ): String = requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

        private fun count(table: String): Long =
            requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun awaitPublicationsSettled() {
            repeat(100) {
                val outstanding =
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                        Long::class.java,
                    ) ?: 0
                if (outstanding == 0L) return
                Thread.sleep(50)
            }
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Long::class.java,
                ),
            ).isZero()
        }

        private fun installAuditFailureTrigger() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_payment_setup_repair_audit() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced payment setup repair audit failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER fail_payment_setup_repair_audit
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION fail_payment_setup_repair_audit()
                """.trimIndent(),
            )
        }

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_payment_setup_repair_audit ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_payment_setup_repair_audit()")
        }

        private data class DamagedSetup(
            val orderId: UUID,
            val caseId: UUID,
            val refundId: UUID,
            val sourceReference: String,
            val providerKey: String,
        )
    }
