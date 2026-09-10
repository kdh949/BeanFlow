package io.github.kdh949.beanflow.dispute.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.MerchantAccountDatabaseFixture
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.ordering.internal.EventPublicationRecoveryWorker
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchLifecycleService
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, SettlementDisputeTestConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies startup, DDL, or committed state across a transaction boundary")
@SpringBootTest(
    properties = [
        "beanflow.store-acceptance.initial-delay-ms=3600000",
        "beanflow.event-publication.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.point-recovery.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.settlement.batch.initial-delay-ms=3600000",
        "beanflow.settlement.dispute.initial-delay-ms=3600000",
    ],
)
internal class SettlementDisputeIntegrationTest
    @Autowired
    constructor(
        private val filing: SettlementDisputeFilingService,
        private val decisions: SettlementDisputeDecisionService,
        private val management: SettlementDisputeManagementService,
        private val passwords: io.github.kdh949.beanflow.identity.internal.CustomerPasswordSecurity,
        private val batchLifecycle: SettlementBatchLifecycleService,
        private val clock: SettlementDisputeTestClock,
        private val publicationRecovery: EventPublicationRecoveryWorker,
        private val jdbcTemplate: JdbcTemplate,
        private val mockMvc: MockMvc,
    ) {
        @BeforeEach
        fun cleanData() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    operations_operator_permission_grant,
                    settlement_dispute,
                    settlement_adjustment,
                    settlement_item,
                    settlement_batch,
                    identity_store_membership,
                    identity_merchant_account,
                    operations_audit_record,
                    operations_reprocessing_case,
                    event_publication,
                    ordering_order,
                    merchant_store
                CASCADE
                """.trimIndent(),
            )
            clock.set(WINDOW_OPEN)
        }

        @Test
        fun `filing persists held Audit event and replays the exact terminal response`() {
            val fixture = fixture()

            val created = file(fixture, "dispute-key-0001")
            val replay = file(fixture, "dispute-key-0001")

            assertThat(replay).isEqualTo(created)
            assertThat(created.state).isEqualTo(SettlementDisputeState.FILED)
            assertThat(created.heldAmountKrw).isEqualTo(-120)
            assertThat(count("SELECT count(*) FROM settlement_dispute")).isOne()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_FILED'"))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1",
                ),
            ).isOne()
            val serialized =
                value<String>(
                    "SELECT serialized_event FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1",
                )
            assertThat(serialized).contains("\"state\":\"FILED\"")
            assertThat(serialized).doesNotContain("evidence:first", fixture.actorId.toString(), "dispute-key-0001")
            clock.set(WINDOW_OPEN.plusSeconds(11))
            publicationRecovery.runOnce()
            await("Dispute filed Operations publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.operations.settlement-dispute-filed-v1",
                ) == 1L
            }

            assertThatThrownBy { file(fixture, "dispute-key-0001", amountKrw = -121) }
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                }
        }

        @Test
        fun `Batch confirmation publication completes after Dispute validates the confirmed public view`() {
            val fixture = fixture()

            publicationRecovery.runOnce()

            await("Batch confirmation publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.dispute.settlement-batch-confirmed-v1",
                ) == 1L
            }
            assertThat(fixture.itemId).isNotEqualTo(UUID(0, 0))
        }

        @Test
        fun `D plus one opens inclusively and D plus fifteen closes exclusively`() {
            val fixture = fixture()

            clock.set(WINDOW_OPEN.minusNanos(1))
            assertWindowClosed { file(fixture, "dispute-key-before") }

            clock.set(WINDOW_CLOSE)
            assertWindowClosed { file(fixture, "dispute-key-close") }

            clock.set(WINDOW_OPEN)
            assertThat(file(fixture, "dispute-key-open").state).isEqualTo(SettlementDisputeState.FILED)
        }

        @Test
        fun `concurrent different keys for one Item converge to one active dispute`() {
            val fixture = fixture()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf("concurrent-key-01", "concurrent-key-02").map { key ->
                        executor.submit<Any> {
                            barrier.await(5, TimeUnit.SECONDS)
                            try {
                                file(fixture, key)
                            } catch (failure: DomainFailure) {
                                failure
                            }
                        }
                    }
                val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(outcomes.count { it is SettlementDisputeResponse }).isOne()
                assertThat(
                    outcomes.filterIsInstance<DomainFailure>().single().code,
                ).isEqualTo(FailureCode.DISPUTE_ALREADY_ACTIVE)
            } finally {
                executor.shutdownNow()
            }
            assertThat(count("SELECT count(*) FROM settlement_dispute WHERE state = 'FILED'")).isOne()
        }

        @Test
        fun `filed publication failure rolls back Dispute held and Audit then retry succeeds`() {
            val fixture = fixture()
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_dispute_filing " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1')",
            )
            try {
                assertThatThrownBy { file(fixture, "filing-failure-key") }
                    .isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_reject_dispute_filing")
            }
            assertThat(count("SELECT count(*) FROM settlement_dispute")).isZero()
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_FILED'"))
                .isZero()

            assertThat(file(fixture, "filing-failure-key").state).isEqualTo(SettlementDisputeState.FILED)
        }

        @Test
        fun `revoked and staff membership cannot file an owner dispute`() {
            val fixture = fixture()
            jdbcTemplate.update(
                "UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ? AND store_id = ?",
                fixture.actorId,
                fixture.storeId,
            )
            assertAccessDenied { file(fixture, "revoked-owner-key") }

            jdbcTemplate.update(
                "UPDATE identity_store_membership SET status = 'ACTIVE', membership_role = 'STAFF' " +
                    "WHERE actor_id = ? AND store_id = ?",
                fixture.actorId,
                fixture.storeId,
            )
            assertAccessDenied {
                filing.file(command(fixture, "staff-member-key", roles = setOf(StoreActorRole.STAFF)))
            }
        }

        @Test
        fun `terminal dispute permits one refile only with a new evidence reference`() {
            val fixture = fixture()
            val first = file(fixture, "first-dispute-key")
            decisions.startReview(first.disputeId)
            decisions.reject(first.disputeId, DECIDED_AT)

            assertRefilingRejected { file(fixture, "missing-previous-key") }
            assertRefilingRejected {
                file(
                    fixture,
                    "same-evidence-key",
                    evidence = listOf("evidence:first"),
                    previousDisputeId = first.disputeId,
                )
            }
            val refile =
                file(
                    fixture,
                    "valid-refile-key",
                    evidence = listOf("evidence:first", "evidence:new"),
                    previousDisputeId = first.disputeId,
                )
            decisions.startReview(refile.disputeId)
            decisions.withdraw(refile.disputeId, DECIDED_AT.plusSeconds(1))

            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM settlement_dispute WHERE state IN ('REJECTED', 'WITHDRAWN') " +
                        "AND held_amount_krw = 0",
                ),
            ).isEqualTo(2)

            assertRefilingRejected {
                file(
                    fixture,
                    "second-refile-key",
                    evidence = listOf("evidence:third"),
                    previousDisputeId = refile.disputeId,
                )
            }
        }

        @Test
        fun `accepted decision waits for Adjustment and recovers after decision publication failure`() {
            val fixture = fixture()
            val dispute = file(fixture, "accepted-dispute-key")
            decisions.startReview(dispute.disputeId)
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_reject_dispute_decision " +
                    "CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1')",
            )
            try {
                assertThatThrownBy { decisions.accept(dispute.disputeId, DECIDED_AT) }
                    .isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_reject_dispute_decision")
            }

            assertThat(value<String>("SELECT state FROM settlement_dispute WHERE id = ?", dispute.disputeId))
                .isEqualTo("UNDER_REVIEW")
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(
                value<String>(
                    "SELECT status FROM operations_reprocessing_case WHERE case_type = 'SETTLEMENT_DISPUTE'",
                ),
            ).isEqualTo("MANUAL_REVIEW")

            val accepted = decisions.accept(dispute.disputeId, DECIDED_AT)

            assertThat(accepted.state).isEqualTo(SettlementDisputeState.ACCEPTED)
            assertThat(accepted.heldAmountKrw).isZero()
            assertThat(accepted.settlementAdjustmentId).isNotNull()
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(
                value<String>(
                    "SELECT status FROM operations_reprocessing_case WHERE case_type = 'SETTLEMENT_DISPUTE'",
                ),
            ).isEqualTo("RESOLVED")
            assertThat(count("SELECT count(*) FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_DECIDED'"))
                .isOne()
            assertThat(
                count(
                    "SELECT count(*) FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1",
                ),
            ).isOne()
            val serialized =
                value<String>(
                    "SELECT serialized_event FROM event_publication WHERE event_type = ?",
                    "io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1",
                )
            assertThat(serialized).doesNotContain("evidence:first", fixture.actorId.toString(), "accepted-dispute-key")
            clock.set(DECIDED_AT.plusSeconds(11))
            publicationRecovery.runOnce()
            await("Dispute decided Operations publication completion") {
                count(
                    "SELECT count(*) FROM event_publication WHERE listener_id = ? AND completion_date IS NOT NULL",
                    "beanflow.operations.settlement-dispute-decided-v1",
                ) == 1L
            }
        }

        @Test
        fun `owner HTTP contract creates FILED response while staff role is forbidden`() {
            val fixture = fixture()
            val body =
                """
                {
                  "expectedAdjustmentKrw": -120,
                  "reason": "fee mismatch",
                  "evidenceReferences": ["evidence:http"]
                }
                """.trimIndent()

            mockMvc
                .perform(
                    post("/api/v1/settlement-items/${fixture.itemId}/disputes")
                        .with(ownerJwt(fixture.actorId))
                        .cookie(jakarta.servlet.http.Cookie("BEANFLOW_MERCHANT_XSRF", "dispute-test-csrf-token"))
                        .header("X-BEANFLOW-CSRF", "dispute-test-csrf-token")
                        .header("Idempotency-Key", "http-dispute-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.settlementItemId").value(fixture.itemId.toString()))
                .andExpect(jsonPath("$.state").value("FILED"))
                .andExpect(jsonPath("$.heldAmountKrw").value(-120))

            mockMvc
                .perform(
                    post("/api/v1/settlement-items/${fixture.itemId}/disputes")
                        .with(staffJwt(UUID.randomUUID()))
                        .cookie(jakarta.servlet.http.Cookie("BEANFLOW_MERCHANT_XSRF", "dispute-test-csrf-token"))
                        .header("X-BEANFLOW-CSRF", "dispute-test-csrf-token")
                        .header("Idempotency-Key", "staff-http-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
        }

        @Test
        fun `management accepts once and replays only while current grant remains active`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-filing-key")
            val operator = grantOperator()
            val review = management.execute(managementCommand(operator, dispute.disputeId, "REVIEW", 0))
            val command = managementCommand(operator, dispute.disputeId, "ACCEPTED", review.version)
            val first = management.execute(command)
            assertThat(first.state).isEqualTo(SettlementDisputeState.ACCEPTED)
            assertThat(management.execute(command)).isEqualTo(first)
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThat(value<String>("SELECT actor_id FROM operations_audit_record WHERE action = 'SETTLEMENT_DISPUTE_DECIDED'"))
                .isEqualTo(operator.toString())
            assertThatThrownBy { management.execute(command.copy(reason = "different reason")) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED) }
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?",
                operator,
            )
            assertAccessDenied { management.execute(command) }
        }

        @Test
        fun `durable approval survives publication rollback and forbids opposite decisions`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-partial-key")
            val operator = grantOperator()
            val review = management.execute(managementCommand(operator, dispute.disputeId, "REVIEW", 0))
            val command = managementCommand(operator, dispute.disputeId, "ACCEPTED", review.version)
            jdbcTemplate.execute(
                "ALTER TABLE event_publication ADD CONSTRAINT test_management_publication_failure CHECK (event_type <> 'io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1')",
            )
            try {
                assertThatThrownBy { management.execute(command) }.isInstanceOf(DomainFailure::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE event_publication DROP CONSTRAINT test_management_publication_failure")
            }
            val pending = management.get(operator, null, dispute.disputeId)
            assertThat(pending.state).isEqualTo(SettlementDisputeState.UNDER_REVIEW)
            assertThat(pending.pendingDecision).isEqualTo(SettlementDisputeState.ACCEPTED)
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
            assertThatThrownBy { management.execute(managementCommand(operator, dispute.disputeId, "REJECTED", pending.version)) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT) }
            assertThatThrownBy { decisions.withdraw(dispute.disputeId, DECIDED_AT) }.isInstanceOf(DomainFailure::class.java)
            val recovered = management.execute(command)
            assertThat(recovered.state).isEqualTo(SettlementDisputeState.ACCEPTED)
            assertThat(recovered.pendingDecision).isNull()
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
        }

        @Test
        fun `management request Audit failure leaves no decision intent or command`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-audit-key")
            decisions.startReview(dispute.disputeId)
            val operator = grantOperator()
            jdbcTemplate.execute(
                "ALTER TABLE operations_audit_record ADD CONSTRAINT test_management_audit_failure CHECK (action <> 'SETTLEMENT_DISPUTE_MANAGEMENT_REQUESTED')",
            )
            try {
                assertThatThrownBy {
                    management.execute(managementCommand(operator, dispute.disputeId, "ACCEPTED", 1))
                }.isInstanceOf(RuntimeException::class.java)
            } finally {
                jdbcTemplate.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_management_audit_failure")
            }
            assertThat(management.get(operator, null, dispute.disputeId).pendingDecision).isNull()
            assertThat(count("SELECT count(*) FROM settlement_dispute_management_command")).isZero()
            assertThat(count("SELECT count(*) FROM settlement_adjustment")).isZero()
        }

        @Test
        fun `owner withdrawal checks membership store boundary version and CSRF`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-withdraw-key")
            decisions.startReview(dispute.disputeId)
            val command = managementCommand(fixture.actorId, dispute.disputeId, "WITHDRAWN", 1).copy(storeId = fixture.storeId)
            val path = "/api/v1/stores/${fixture.storeId}/disputes/${dispute.disputeId}/withdrawals"
            val password = "merchant-management-password-2026"
            val loginId = "management.owner"
            jdbcTemplate.update(
                "UPDATE identity_merchant_account SET login_id = ?, password_hash = ? WHERE id = ?",
                loginId,
                passwords.encode(password),
                fixture.actorId,
            )
            val csrfCookie =
                requireNotNull(
                    mockMvc
                        .perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/auth/merchant/csrf"),
                        ).andReturn()
                        .response
                        .getCookie("BEANFLOW_MERCHANT_XSRF"),
                )
            val session =
                requireNotNull(
                    mockMvc
                        .perform(
                            post(
                                "/api/v1/auth/merchant/sessions",
                            ).cookie(
                                csrfCookie,
                            ).header(
                                "X-BEANFLOW-CSRF",
                                csrfCookie.value,
                            ).contentType(MediaType.APPLICATION_JSON)
                                .content("""{"loginId":"$loginId","password":"$password"}"""),
                        ).andExpect(status().isOk)
                        .andReturn()
                        .response
                        .getCookie("BEANFLOW_MERCHANT_SESSION"),
                )
            mockMvc
                .perform(
                    post(
                        path,
                    ).cookie(
                        session,
                        csrfCookie,
                    ).header(
                        "Idempotency-Key",
                        command.key,
                    ).contentType(MediaType.APPLICATION_JSON)
                        .content("""{"expectedVersion":1,"reason":"withdraw evidence"}"""),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(
                    post(
                        path,
                    ).cookie(
                        session,
                        csrfCookie,
                    ).header(
                        "X-BEANFLOW-CSRF",
                        csrfCookie.value,
                    ).header(
                        "Idempotency-Key",
                        command.key,
                    ).contentType(MediaType.APPLICATION_JSON)
                        .content("""{"expectedVersion":1,"reason":"withdraw evidence"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("WITHDRAWN"))
            val other = fixture()
            assertThatThrownBy { management.get(other.actorId, other.storeId, dispute.disputeId) }
                .isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(FailureCode.RESOURCE_NOT_FOUND) }
        }

        @Test
        fun `operator HTTP review and decision require purpose grants and reject stale or unknown payload`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-http-filing")
            val operator = grantOperator()
            val actor = jwt().jwt { it.subject(operator.toString()) }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))
            val path = "/api/v1/operations/settlement-disputes/${dispute.disputeId}"
            mockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(path)
                        .with(actor),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(0))
            mockMvc
                .perform(
                    post(
                        "$path/reviews",
                    ).with(
                        actor,
                    ).header(
                        "Idempotency-Key",
                        "review-http-key",
                    ).contentType(MediaType.APPLICATION_JSON)
                        .content("""{"expectedVersion":0,"reason":"review evidence"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("UNDER_REVIEW"))
            mockMvc
                .perform(
                    post(
                        "$path/decisions",
                    ).with(
                        actor,
                    ).header(
                        "Idempotency-Key",
                        "decision-http-key",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"outcome":"REJECTED","expectedVersion":0,"reason":"review evidence"}"""),
                ).andExpect(status().isConflict)
            mockMvc
                .perform(
                    post(
                        "$path/decisions",
                    ).with(
                        actor,
                    ).header(
                        "Idempotency-Key",
                        "decision-http-key",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"outcome":"REJECTED","expectedVersion":1,"reason":"review evidence","amountKrw":999}"""),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post(
                        "$path/decisions",
                    ).with(
                        actor,
                    ).header(
                        "Idempotency-Key",
                        "decision-http-key",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"outcome":"REJECTED","expectedVersion":1,"reason":"review evidence"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REJECTED"))
        }

        @Test
        fun `concurrent approval keys create one adjustment and one terminal decision`() {
            val fixture = fixture()
            val dispute = file(fixture, "management-race-filing")
            decisions.startReview(dispute.disputeId)
            val operator = grantOperator()
            val command = managementCommand(operator, dispute.disputeId, "ACCEPTED", 1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val barrier = CyclicBarrier(2)
                val futures =
                    (1..2).map {
                        executor.submit<DisputeManagementResponse> {
                            barrier.await(5, TimeUnit.SECONDS)
                            management.execute(command)
                        }
                    }
                val responses = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertThat(responses[0]).isEqualTo(responses[1])
                assertThat(count("SELECT count(*) FROM settlement_adjustment")).isOne()
                assertThat(count("SELECT count(*) FROM settlement_dispute_management_command")).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        private fun managementCommand(
            actor: UUID,
            dispute: UUID,
            operation: String,
            version: Long,
        ) = DisputeManagementCommand(
            actor,
            null,
            dispute,
            operation,
            "management-$operation-$dispute",
            version,
            "verified evidence",
            "management-test",
        )

        private fun grantOperator(): UUID =
            UUID.randomUUID().also { actor ->
                listOf("SETTLEMENT_DISPUTE_READ", "SETTLEMENT_DISPUTE_DECIDE").forEach { permission ->
                    jdbcTemplate.update(
                        "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', ?, 1, ?)",
                        actor,
                        permission,
                        Timestamp.from(WINDOW_OPEN),
                        "management:$actor:$permission",
                    )
                }
            }

        private fun fixture(): Fixture {
            val storeId = insertStore()
            val actorId = UUID.randomUUID()
            insertMembership(actorId, storeId)
            val orderId = insertCompletedOrder(storeId)
            val batchId = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO settlement_batch (id, store_id, settlement_date, state, created_at, version) " +
                    "VALUES (?, ?, ?, 'OPEN', ?, 0)",
                batchId,
                storeId,
                SETTLEMENT_DATE,
                Timestamp.from(COMPLETED_AT),
            )
            jdbcTemplate.update(
                """
                INSERT INTO settlement_item (
                    id, settlement_batch_id, order_id, store_id, item_source,
                    completed_at, settlement_date, currency,
                    gross_paid_krw, fee_rate_bps, fee_krw,
                    coupon_cost_krw, point_cost_krw, benefit_cost_krw,
                    net_settlement_krw, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'KRW', 1000, 500, 50, 25, 25, 50, 900, ?)
                """.trimIndent(),
                itemId,
                batchId,
                orderId,
                storeId,
                "order:$orderId:completed:7",
                Timestamp.from(COMPLETED_AT),
                SETTLEMENT_DATE,
                Timestamp.from(COMPLETED_AT),
            )
            batchLifecycle.calculate(batchId, CALCULATED_AT)
            batchLifecycle.confirm(batchId, CONFIRMED_AT, "dispute-fixture-confirmation")
            return Fixture(actorId, storeId, itemId)
        }

        private fun file(
            fixture: Fixture,
            key: String,
            amountKrw: Long = -120,
            evidence: List<String> = listOf("evidence:first"),
            previousDisputeId: UUID? = null,
        ): SettlementDisputeResponse = filing.file(command(fixture, key, amountKrw, evidence, previousDisputeId))

        private fun command(
            fixture: Fixture,
            key: String,
            amountKrw: Long = -120,
            evidence: List<String> = listOf("evidence:first"),
            previousDisputeId: UUID? = null,
            roles: Set<StoreActorRole> = setOf(StoreActorRole.OWNER),
        ): FileSettlementDisputeCommand =
            FileSettlementDisputeCommand(
                actorId = fixture.actorId,
                actorRoles = roles,
                settlementItemId = fixture.itemId,
                idempotencyKey = key,
                expectedAdjustmentKrw = amountKrw,
                reason = "settlement amount mismatch",
                evidenceReferences = evidence,
                previousDisputeId = previousDisputeId,
                correlationId = "dispute-test-correlation",
            )

        private fun insertStore(): UUID =
            UUID.randomUUID().also {
                jdbcTemplate.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) " +
                        "VALUES (?, true, true, 0)",
                    it,
                )
            }

        private fun insertMembership(
            actorId: UUID,
            storeId: UUID,
        ) {
            MerchantAccountDatabaseFixture.insertActive(jdbcTemplate, actorId)
            jdbcTemplate.update(
                """
                INSERT INTO identity_store_membership (
                    id, actor_id, store_id, membership_role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(),
                actorId,
                storeId,
                Timestamp.from(COMPLETED_AT),
                Timestamp.from(COMPLETED_AT),
            )
        }

        private fun insertCompletedOrder(storeId: UUID): UUID =
            UUID.randomUUID().also { orderId ->
                val publicReference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, orderId)
                jdbcTemplate.execute("ALTER TABLE ordering_order DISABLE TRIGGER USER")
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO ordering_order (
                            id, customer_id, store_id, pickup_slot_id,
                            public_reference, pickup_business_date, pickup_sequence,
                            store_name_snapshot, pickup_window_start_snapshot, pickup_window_end_snapshot,
                            state,
                            subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw,
                            currency, reservation_expires_at, paid_at, acceptance_warning_at,
                            acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                            created_at, updated_at, version
                        ) VALUES (?, ?, ?, ?, ?, DATE '2026-08-03', ?,
                                  'Test Store', '2026-08-03T00:00:00Z', '2026-08-03T00:10:00Z',
                                  'COMPLETED', 1000, 0, 0, 1000,
                                  'KRW', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 7)
                        """.trimIndent(),
                        orderId,
                        UUID.randomUUID(),
                        storeId,
                        UUID.randomUUID(),
                        publicReference,
                        OrderCreationDatabaseFixture.pickupSequence(orderId),
                        Timestamp.from(COMPLETED_AT.minusSeconds(180)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(120)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(60)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(150)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(90)),
                        Timestamp.from(COMPLETED_AT.minusSeconds(30)),
                        Timestamp.from(COMPLETED_AT),
                        Timestamp.from(COMPLETED_AT.minusSeconds(300)),
                        Timestamp.from(COMPLETED_AT),
                    )
                } finally {
                    jdbcTemplate.execute("ALTER TABLE ordering_order ENABLE TRIGGER USER")
                }
            }

        private fun ownerJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_OWNER")) }
                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))

        private fun staffJwt(actorId: UUID) =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("STORE_STAFF")) }
                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))

        private fun assertWindowClosed(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DISPUTE_WINDOW_CLOSED)
                }
        }

        private fun assertAccessDenied(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
                }
        }

        private fun assertRefilingRejected(block: () -> Unit) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) {
                    assertThat(it.code).isEqualTo(FailureCode.DISPUTE_REFILE_NOT_ALLOWED)
                }
        }

        private fun count(
            sql: String,
            vararg arguments: Any,
        ): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *arguments))

        private inline fun <reified T : Any> value(
            sql: String,
            vararg arguments: Any,
        ): T = requireNotNull(jdbcTemplate.queryForObject(sql, T::class.java, *arguments))

        private fun await(
            description: String,
            assertion: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (runCatching(assertion).getOrDefault(false)) return
                Thread.sleep(20)
            }
            check(assertion()) { "Timed out waiting for $description" }
        }

        private data class Fixture(
            val actorId: UUID,
            val storeId: UUID,
            val itemId: UUID,
        )

        private companion object {
            val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
            val COMPLETED_AT: Instant = Instant.parse("2026-08-03T01:00:00Z")
            val SETTLEMENT_DATE: LocalDate = COMPLETED_AT.atZone(SEOUL).toLocalDate()
            val CALCULATED_AT: Instant = Instant.parse("2026-08-04T00:00:00Z")
            val CONFIRMED_AT: Instant = Instant.parse("2026-08-04T00:01:00Z")
            val WINDOW_OPEN: Instant = Instant.parse("2026-08-04T15:00:00Z")
            val WINDOW_CLOSE: Instant = Instant.parse("2026-08-18T15:00:00Z")
            val DECIDED_AT: Instant = Instant.parse("2026-08-06T00:00:00Z")
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class SettlementDisputeTestConfiguration {
    @Bean
    @Primary
    fun settlementDisputeTestClock(): SettlementDisputeTestClock = SettlementDisputeTestClock()
}

internal class SettlementDisputeTestClock : Clock() {
    private val current = AtomicReference(Instant.parse("2026-08-04T15:00:00Z"))

    fun set(now: Instant) {
        current.set(now)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()
}
