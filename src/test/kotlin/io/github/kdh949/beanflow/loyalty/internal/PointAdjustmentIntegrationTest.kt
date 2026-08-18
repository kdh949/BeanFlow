package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.loyalty.api.ApplyPointAdjustmentCommand
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentIssuer
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentOperations
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentResult
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.groups.Tuple.tuple
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
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies committed state across a transaction or thread boundary")
@SpringBootTest(
    properties = [
        "beanflow.loyalty-point-adjustment-retention.initial-delay-ms=3600000",
        "beanflow.audit-retention.initial-delay-ms=3600000",
        "beanflow.notification.initial-delay-ms=3600000",
        "beanflow.payment.refund.initial-delay-ms=3600000",
        "beanflow.payment.reconciliation.initial-delay-ms=3600000",
        "beanflow.reservation-expiry.initial-delay-ms=3600000",
        "beanflow.store-acceptance.initial-delay-ms=3600000",
    ],
)
internal class PointAdjustmentIntegrationTest
    @Autowired
    constructor(
        private val operations: PointAdjustmentOperations,
        private val jdbcTemplate: JdbcTemplate,
        private val mockMvc: MockMvc,
        private val clock: Clock,
        private val retentionService: PointAdjustmentIdempotencyRetentionService,
        private val retentionWorker: LoyaltyPointAdjustmentIdempotencyRetentionWorker,
    ) {
        private val actorId = UUID.fromString("20000000-0000-0000-0000-000000000069")

        @BeforeEach
        fun cleanDatabase() {
            dropFailureTrigger()
            jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS test_point_adjustment_retention_failure " +
                    "ON loyalty_point_adjustment_command_idempotency",
            )
            cleanFinancialRows()
            jdbcTemplate.update(
                "DELETE FROM operations_operator_permission_grant WHERE actor_id = ? AND permission = 'POINT_ADJUSTMENT'",
                actorId,
            )
            grant(actorId)
        }

        @Test
        fun `credit snapshots issuer writes atomic evidence and replays the exact first response`() {
            val accountId = insertAccount(available = 0)
            val command = creditCommand(accountId, key = "credit-idempotency-0001", expiresAt = NOW.plusNanos(1))

            val first = operations.adjust(command)
            val replay = operations.adjust(command)

            assertThat(replay).isEqualTo(first)
            assertThat(first.account.availablePointsKrw).isEqualTo(125)
            assertThat(first.account.recoveryPendingKrw).isZero()
            assertThat(first.transactions).hasSize(1)
            assertThat(first.transactions.single().amountKrw).isEqualTo(125)
            val lot = jdbcTemplate.queryForMap("SELECT * FROM loyalty_point_lot WHERE point_account_id = ?", accountId)
            assertThat(lot["available_amount_krw"]).isEqualTo(125L)
            assertThat(lot["reserved_amount_krw"]).isEqualTo(0L)
            assertThat(lot["issuer_type"]).isEqualTo("STORE")
            assertThat(lot["issuer_reference"]).isEqualTo("store:audited-42")
            assertThat(
                jdbcTemplate.queryForList(
                    "SELECT type, balance_effect, amount_krw FROM loyalty_point_transaction WHERE point_account_id = ?",
                    accountId,
                ),
            ).extracting("type", "balance_effect", "amount_krw")
                .containsExactly(tuple("ADJUSTMENT", "CREDIT", 125L))
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
            assertThat(count("operations_audit_record")).isOne()
            assertThat(count("event_publication")).isOne()

            val audit = jdbcTemplate.queryForMap("SELECT * FROM operations_audit_record")
            assertThat(audit["actor_id"]).isEqualTo(actorId.toString())
            assertThat(audit["actor_type"]).isEqualTo("PLATFORM_OPERATOR")
            assertThat(audit["action"]).isEqualTo("POINT_ADJUSTMENT_APPLIED")
            assertThat(audit["reason"]).isEqualTo("Verified correction")
            assertThat(audit["after_summary"].toString())
                .contains("evidenceReferences", "evidence:ticket-42", "issuerType", "STORE")
            val event = jdbcTemplate.queryForObject("SELECT serialized_event FROM event_publication", String::class.java)!!
            assertThat(event)
                .contains("\"eventType\":\"PointsAdjustedV1\"", "\"amountKrw\":125", "\"issuerType\":\"STORE\"")
                .doesNotContain(actorId.toString(), command.idempotencyKey, "evidence:ticket-42", "store:audited-42")

            assertFailure(FailureCode.IDEMPOTENCY_KEY_REUSED) {
                operations.adjust(command.copy(reason = "Changed correction reason"))
            }
            assertThat(count("loyalty_point_lot")).isOne()
            assertThat(count("loyalty_point_transaction")).isOne()
            assertThat(count("event_publication")).isOne()
        }

        @Test
        fun `credit expiry must be strictly after command time`() {
            listOf(NOW.minusNanos(1), NOW).forEachIndexed { index, expiresAt ->
                val accountId = insertAccount(available = 0)
                assertFailure(FailureCode.INVALID_REQUEST) {
                    operations.adjust(creditCommand(accountId, "expiry-invalid-000$index", expiresAt))
                }
            }

            assertThat(count("loyalty_point_lot")).isZero()
            assertThat(count("loyalty_point_transaction")).isZero()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
        }

        @Test
        fun `debit consumes only unexpired available lots in expiry and id order`() {
            val accountId = insertAccount(available = 80, reserved = 30)
            val expired = insertLot(accountId, LOT_LOW, available = 10, expiresAt = NOW)
            val earliest = insertLot(accountId, LOT_HIGH, available = 20, reserved = 30, expiresAt = NOW.plusSeconds(10))
            val second = insertLot(accountId, LOT_MIDDLE, available = 50, expiresAt = NOW.plusSeconds(20))

            val result = operations.adjust(debitCommand(accountId, amount = -60))

            assertThat(result.account.availablePointsKrw).isEqualTo(20)
            assertThat(result.transactions.map { it.amountKrw }).containsExactly(-20, -40)
            assertThat(result.transactions.map { it.sourceReference })
                .allMatch { it.matches(ADJUSTMENT_CHILD_SOURCE) }
            assertThat(lotAvailable(expired)).isEqualTo(10)
            assertThat(lotAvailable(earliest)).isZero()
            assertThat(lotAvailable(second)).isEqualTo(10)
            assertThat(long("SELECT reserved_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isEqualTo(30)
            assertThat(long("SELECT reserved_amount_krw FROM loyalty_point_lot WHERE id = ?", earliest)).isEqualTo(30)
            assertThat(
                jdbcTemplate.queryForList(
                    """
                    SELECT point_lot_id, balance_effect, amount_krw
                      FROM loyalty_point_transaction
                     WHERE point_account_id = ? ORDER BY occurred_at, id
                    """.trimIndent(),
                    accountId,
                ),
            ).extracting("point_lot_id", "balance_effect", "amount_krw")
                .containsExactlyInAnyOrder(
                    tuple(earliest, "DEBIT", 20L),
                    tuple(second, "DEBIT", 40L),
                )
            val event = jdbcTemplate.queryForObject("SELECT serialized_event FROM event_publication", String::class.java)!!
            assertThat(event).contains("\"amountKrw\":-60").doesNotContain("issuerType")
        }

        @Test
        fun `insufficient unexpired available balance rolls back without partial debit`() {
            val accountId = insertAccount(available = 70)
            val expired = insertLot(accountId, LOT_LOW, available = 50, expiresAt = NOW)
            val available = insertLot(accountId, LOT_HIGH, available = 20, expiresAt = NOW.plusSeconds(1))

            assertFailure(FailureCode.POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE) {
                operations.adjust(debitCommand(accountId, amount = -30))
            }

            assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isEqualTo(70)
            assertThat(lotAvailable(expired)).isEqualTo(50)
            assertThat(lotAvailable(available)).isEqualTo(20)
            assertThat(count("loyalty_point_transaction")).isZero()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
            assertThat(count("operations_audit_record")).isZero()
            assertThat(count("event_publication")).isZero()
        }

        @Test
        fun `concurrent debits serialize on the point account and never make a negative balance`() {
            val accountId = insertAccount(available = 100)
            insertLot(accountId, LOT_LOW, available = 100, expiresAt = NOW.plusSeconds(100))
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf("concurrent-debit-0001", "concurrent-debit-0002").map { key ->
                        executor.submit<PointAdjustmentResult> {
                            barrier.await()
                            operations.adjust(debitCommand(accountId, key = key, amount = -80))
                        }
                    }
                val outcomes =
                    futures.map { future ->
                        runCatching { future.get(10, TimeUnit.SECONDS) }
                            .exceptionOrNull()
                            ?.let { (it as ExecutionException).cause }
                            ?: future.get(10, TimeUnit.SECONDS)
                    }

                assertThat(outcomes.filterIsInstance<PointAdjustmentResult>()).hasSize(1)
                assertThat(outcomes.filterIsInstance<DomainFailure>().single().code)
                    .isEqualTo(FailureCode.POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE)
                assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isEqualTo(20)
                assertThat(long("SELECT available_amount_krw FROM loyalty_point_lot WHERE point_account_id = ?", accountId))
                    .isEqualTo(20)
                assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `same actor and key across accounts serialize on the grant and commit only one command`() {
            val firstAccount = insertAccount(available = 0)
            val secondAccount = insertAccount(available = 0)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val outcomes =
                    listOf(firstAccount, secondAccount)
                        .map { accountId ->
                            executor.submit<PointAdjustmentResult> {
                                barrier.await()
                                operations.adjust(creditCommand(accountId, "cross-account-key-0001", NOW.plusSeconds(1)))
                            }
                        }.map { future ->
                            try {
                                future.get(10, TimeUnit.SECONDS)
                            } catch (failure: ExecutionException) {
                                failure.cause
                            }
                        }

                assertThat(outcomes.filterIsInstance<PointAdjustmentResult>()).hasSize(1)
                assertThat(outcomes.filterIsInstance<DomainFailure>().single().code)
                    .isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
                assertThat(count("loyalty_point_lot")).isOne()
                assertThat(count("loyalty_point_transaction")).isOne()
                assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
                assertThat(count("operations_audit_record")).isOne()
                assertThat(count("event_publication")).isOne()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `every owner persistence failure rolls back the entire credit adjustment`() {
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_point_adjustment_write()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected point adjustment failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            val failurePoints =
                listOf(
                    "loyalty_point_account" to "UPDATE",
                    "loyalty_point_lot" to "INSERT",
                    "loyalty_point_transaction" to "INSERT",
                    "operations_audit_record" to "INSERT",
                    "event_publication" to "INSERT",
                    "loyalty_point_adjustment_command_idempotency" to "INSERT",
                )

            failurePoints.forEachIndexed { index, (table, event) ->
                cleanFinancialRows()
                val accountId = insertAccount(available = 0)
                jdbcTemplate.execute(
                    "CREATE TRIGGER test_point_adjustment_failure BEFORE $event ON $table " +
                        "FOR EACH ROW EXECUTE FUNCTION test_reject_point_adjustment_write()",
                )
                try {
                    assertFailure(FailureCode.DEPENDENCY_UNAVAILABLE) {
                        operations.adjust(
                            creditCommand(accountId, "atomic-failure-${index.toString().padStart(4, '0')}", NOW.plusSeconds(1)),
                        )
                    }
                } finally {
                    dropFailureTrigger(table)
                }
                assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isZero()
                assertThat(count("loyalty_point_lot")).isZero()
                assertThat(count("loyalty_point_transaction")).isZero()
                assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
                assertThat(count("operations_audit_record")).isZero()
                assertThat(count("event_publication")).isZero()
            }
        }

        @Test
        fun `operations endpoint requires platform role key reason evidence and conditional fields`() {
            val accountId = insertAccount(available = 0)
            val success =
                mockMvc
                    .perform(
                        post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                            .with(operatorJwt())
                            .header("Idempotency-Key", "controller-credit-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(creditRequestJson()),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.account.accountId").value(accountId.toString()))
                    .andExpect(jsonPath("$.account.availablePointsKrw").value(125))
                    .andExpect(jsonPath("$.transactions[0].amountKrw").value(125))
                    .andReturn()
                    .response
            val replay =
                mockMvc
                    .perform(
                        post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                            .with(operatorJwt())
                            .header("Idempotency-Key", "controller-credit-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(creditRequestJson()),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            assertThat(replay.contentAsString).isEqualTo(success.contentAsString)

            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditRequestJson()),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "controller-invalid-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"amountKrw":10,"reason":"reason","evidenceReferences":["evidence"]}"""),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "controller-invalid-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "amountKrw": -10,
                              "issuer": {"issuerType": "PLATFORM", "issuerReference": "platform:test"},
                              "expiresAt": "2030-01-01T00:00:00Z",
                              "reason": "reason",
                              "evidenceReferences": ["evidence"]
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "controller-invalid-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"amountKrw":-10,"reason":" ","evidenceReferences":[]}"""),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "controller-invalid-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"amountKrw":-10,"reason":"reason","evidenceReferences":["evidence"],"unexpected":true}""",
                        ),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `reason length 160 succeeds and 161 returns bad request over PostgreSQL HTTP`() {
            val acceptedAccount = insertAccount(available = 0)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", acceptedAccount)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "reason-boundary-accepted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditRequestJson(reason = "r".repeat(POINT_ADJUSTMENT_REASON_MAX_LENGTH))),
                ).andExpect(status().isCreated)

            val rejectedAccount = insertAccount(available = 0)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", rejectedAccount)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "reason-boundary-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditRequestJson(reason = "r".repeat(POINT_ADJUSTMENT_REASON_MAX_LENGTH + 1))),
                ).andExpect(status().isBadRequest)
            assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", rejectedAccount)).isZero()
        }

        @Test
        fun `twenty evidence references succeed and twenty one return bad request over PostgreSQL HTTP`() {
            val acceptedEvidence = (1..POINT_ADJUSTMENT_EVIDENCE_MAX_COUNT).map { "evidence:$it" }
            val acceptedAccount = insertAccount(available = 0)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", acceptedAccount)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "evidence-boundary-accepted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditRequestJson(evidenceReferences = acceptedEvidence)),
                ).andExpect(status().isCreated)

            val rejectedAccount = insertAccount(available = 0)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", rejectedAccount)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "evidence-boundary-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditRequestJson(evidenceReferences = acceptedEvidence + "evidence:overflow")),
                ).andExpect(status().isBadRequest)
            assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", rejectedAccount)).isZero()
        }

        @Test
        fun `non HTTP commands enforce audit reason and evidence limits`() {
            val reasonAccount = insertAccount(available = 0)
            assertFailure(FailureCode.INVALID_REQUEST) {
                operations.adjust(
                    creditCommand(reasonAccount, "service-reason-limit-0001", NOW.plusSeconds(1))
                        .copy(reason = "r".repeat(POINT_ADJUSTMENT_REASON_MAX_LENGTH + 1)),
                )
            }
            val evidenceAccount = insertAccount(available = 0)
            assertFailure(FailureCode.INVALID_REQUEST) {
                operations.adjust(
                    creditCommand(evidenceAccount, "service-evidence-limit-0001", NOW.plusSeconds(1))
                        .copy(
                            evidenceReferences =
                                (1..POINT_ADJUSTMENT_EVIDENCE_MAX_COUNT + 1).map { "evidence:$it" },
                        ),
                )
            }
            assertThat(count("loyalty_point_transaction")).isZero()
        }

        @Test
        fun `negative long max succeeds and long min returns bad request over PostgreSQL HTTP`() {
            val acceptedAccount = insertAccount(available = Long.MAX_VALUE)
            insertLot(
                acceptedAccount,
                LOT_LOW,
                available = Long.MAX_VALUE,
                expiresAt = clock.instant().plusSeconds(60),
            )
            val accepted =
                mockMvc
                    .perform(
                        post("/api/v1/operations/point-accounts/{accountId}/adjustments", acceptedAccount)
                            .with(operatorJwt())
                            .header("Idempotency-Key", "amount-boundary-accepted")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(debitRequestJson(-Long.MAX_VALUE)),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.account.availablePointsKrw").value(0))
                    .andReturn()
                    .response
            assertThat(accepted.contentAsString).contains("\"amountKrw\":-${Long.MAX_VALUE}")

            val rejectedAccount = insertAccount(available = 0)
            mockMvc
                .perform(
                    post("/api/v1/operations/point-accounts/{accountId}/adjustments", rejectedAccount)
                        .with(operatorJwt())
                        .header("Idempotency-Key", "amount-boundary-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitRequestJson(Long.MIN_VALUE)),
                ).andExpect(status().isBadRequest)
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
        }

        @Test
        fun `non idempotency check violation remains unavailable over PostgreSQL HTTP`() {
            val accountId = insertAccount(available = 0)
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_point_adjustment_write()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected non-idempotency check failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_point_adjustment_failure
                BEFORE INSERT ON operations_audit_record
                FOR EACH ROW EXECUTE FUNCTION test_reject_point_adjustment_write()
                """.trimIndent(),
            )
            try {
                performCredit(accountId, "non-idempotency-check-0001")
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            } finally {
                dropFailureTrigger("operations_audit_record")
            }
            assertThat(long("SELECT available_points_krw FROM loyalty_point_account WHERE id = ?", accountId)).isZero()
            assertThat(count("loyalty_point_lot")).isZero()
            assertThat(count("loyalty_point_transaction")).isZero()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
            assertThat(count("operations_audit_record")).isZero()
            assertThat(count("event_publication")).isZero()
        }

        @Test
        fun `customer store and settlement roles cannot call the adjustment endpoint`() {
            val accountId = insertAccount(available = 0)
            listOf("CUSTOMER", "STORE_OWNER", "STORE_STAFF", "SETTLEMENT_OPERATOR").forEach { role ->
                mockMvc
                    .perform(
                        post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                            .with(
                                jwt()
                                    .jwt { it.subject(UUID.randomUUID().toString()).claim("roles", listOf(role)) }
                                    .authorities(SimpleGrantedAuthority("ROLE_$role")),
                            ).header("Idempotency-Key", "role-denial-${role.lowercase()}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(creditRequestJson()),
                    ).andExpect(status().isForbidden)
            }
            assertThat(count("loyalty_point_transaction")).isZero()
        }

        @Test
        fun `revoked grant is forbidden and grant lookup failure is unavailable`() {
            val revokedAccount = insertAccount(available = 0)
            jdbcTemplate.update(
                """
                UPDATE operations_operator_permission_grant
                   SET state = 'REVOKED', revoked_at = ?, version = version + 1
                 WHERE actor_id = ? AND permission = 'POINT_ADJUSTMENT'
                """.trimIndent(),
                Timestamp.from(NOW),
                actorId,
            )
            performCredit(revokedAccount, "revoked-grant-0001")
                .andExpect(status().isForbidden)
            assertThat(count("loyalty_point_transaction")).isZero()

            jdbcTemplate.update(
                """
                UPDATE operations_operator_permission_grant
                   SET state = 'ACTIVE', revoked_at = NULL, version = version + 1
                 WHERE actor_id = ? AND permission = 'POINT_ADJUSTMENT'
                """.trimIndent(),
                actorId,
            )
            val failureAccount = insertAccount(available = 0)
            jdbcTemplate.execute("ALTER TABLE operations_operator_permission_grant RENAME TO test_unavailable_operator_grant")
            try {
                performCredit(failureAccount, "grant-unavailable-0001")
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            } finally {
                jdbcTemplate.execute("ALTER TABLE test_unavailable_operator_grant RENAME TO operations_operator_permission_grant")
            }
            assertThat(count("loyalty_point_transaction")).isZero()
        }

        @Test
        fun `terminal idempotency retention honors the 90 day boundary and chunks at 100`() {
            val accountId = insertAccount(available = 0)
            repeat(100) { index ->
                insertIdempotency(
                    accountId = accountId,
                    key = "retention-due-${index.toString().padStart(4, '0')}",
                    createdAt = NOW.minusSeconds(90L * 24 * 60 * 60 + index + 1),
                )
            }
            insertIdempotency(accountId, "retention-boundary-0001", NOW.minusSeconds(90L * 24 * 60 * 60))
            val futureId =
                insertIdempotency(
                    accountId,
                    "retention-future-0001",
                    NOW.minusSeconds(90L * 24 * 60 * 60).plusSeconds(1),
                )

            val first = retentionService.purgeDue(NOW, 100)
            val second = retentionService.purgeDue(NOW, 100)

            assertThat(first.deletedCount).isEqualTo(100)
            assertThat(second.deletedCount).isOne()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM loyalty_point_adjustment_command_idempotency WHERE id = ?",
                    Long::class.java,
                    futureId,
                ),
            ).isOne()
        }

        @Test
        fun `retention cleanup failure preserves due rows and a later run retries them`() {
            val accountId = insertAccount(available = 0)
            insertIdempotency(
                accountId,
                "retention-retry-0001",
                Instant.now().minusSeconds(91L * 24 * 60 * 60),
            )
            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_point_adjustment_delete()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected retention failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                """
                CREATE TRIGGER test_point_adjustment_retention_failure
                BEFORE DELETE ON loyalty_point_adjustment_command_idempotency
                FOR EACH ROW EXECUTE FUNCTION test_reject_point_adjustment_delete()
                """.trimIndent(),
            )

            val failed = retentionWorker.runOnce()

            assertThat(failed.outcome).isEqualTo(PointAdjustmentRetentionOutcome.FAILED)
            assertThat(failed.deletedCount).isNull()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isOne()
            jdbcTemplate.execute(
                "DROP TRIGGER test_point_adjustment_retention_failure ON loyalty_point_adjustment_command_idempotency",
            )

            val retried = retentionWorker.runOnce()

            assertThat(retried.outcome).isEqualTo(PointAdjustmentRetentionOutcome.SUCCEEDED)
            assertThat(retried.deletedCount).isOne()
            assertThat(count("loyalty_point_adjustment_command_idempotency")).isZero()
        }

        private fun creditCommand(
            accountId: UUID,
            key: String,
            expiresAt: Instant,
        ): ApplyPointAdjustmentCommand =
            ApplyPointAdjustmentCommand(
                actorId = actorId,
                pointAccountId = accountId,
                idempotencyKey = key,
                amountKrw = 125,
                issuer = PointAdjustmentIssuer(PointIssuerType.STORE, "store:audited-42"),
                expiresAt = expiresAt,
                reason = "Verified correction",
                evidenceReferences = listOf("evidence:ticket-42"),
                correlationId = "correlation:point-adjustment",
                now = NOW,
            )

        private fun operatorJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private fun performCredit(
            accountId: UUID,
            key: String,
        ) = mockMvc.perform(
            post("/api/v1/operations/point-accounts/{accountId}/adjustments", accountId)
                .with(operatorJwt())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(creditRequestJson()),
        )

        private fun creditRequestJson(
            reason: String = "Verified correction",
            evidenceReferences: List<String> = listOf("evidence:ticket-42"),
        ): String =
            """
            {
              "amountKrw": 125,
              "issuer": {"issuerType": "STORE", "issuerReference": "store:audited-42"},
              "expiresAt": "2030-01-01T00:00:00Z",
              "reason": "$reason",
              "evidenceReferences": ${evidenceReferences.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")}
            }
            """.trimIndent()

        private fun debitRequestJson(amountKrw: Long): String =
            """
            {
              "amountKrw": $amountKrw,
              "reason": "Verified debit correction",
              "evidenceReferences": ["evidence:ticket-debit"]
            }
            """.trimIndent()

        private fun debitCommand(
            accountId: UUID,
            key: String = "debit-idempotency-0001",
            amount: Long,
        ): ApplyPointAdjustmentCommand =
            ApplyPointAdjustmentCommand(
                actorId = actorId,
                pointAccountId = accountId,
                idempotencyKey = key,
                amountKrw = amount,
                issuer = null,
                expiresAt = null,
                reason = "Verified debit correction",
                evidenceReferences = listOf("evidence:ticket-debit"),
                correlationId = "correlation:point-adjustment-debit",
                now = NOW,
            )

        private fun grant(actorId: UUID) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, 'POINT_ADJUSTMENT', 'ACTIVE', ?, NULL, 1, ?)
                """.trimIndent(),
                actorId,
                Timestamp.from(NOW.minusSeconds(1)),
                "test:point-adjustment-grant:$actorId",
            )
        }

        private fun insertAccount(
            available: Long,
            reserved: Long = 0,
        ): UUID =
            UUID.randomUUID().also { accountId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_account (
                        id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw, version
                    ) VALUES (?, ?, ?, ?, 0, 0)
                    """.trimIndent(),
                    accountId,
                    UUID.randomUUID(),
                    available,
                    reserved,
                )
            }

        private fun insertLot(
            accountId: UUID,
            lotId: UUID,
            available: Long,
            reserved: Long = 0,
            expiresAt: Instant,
        ): UUID =
            lotId.also {
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_lot (
                        id, point_account_id, available_amount_krw, reserved_amount_krw,
                        expires_at, issuer_type, issuer_reference, version
                    ) VALUES (?, ?, ?, ?, ?, 'PLATFORM', ?, 0)
                    """.trimIndent(),
                    lotId,
                    accountId,
                    available,
                    reserved,
                    Timestamp.from(expiresAt),
                    "platform:test:$lotId",
                )
            }

        private fun insertIdempotency(
            accountId: UUID,
            key: String,
            createdAt: Instant,
        ): UUID =
            UUID.randomUUID().also { id ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_adjustment_command_idempotency (
                        id, actor_id, point_account_id, operation, idempotency_key, payload_hash,
                        response_status, response_body, response_version, created_at, retention_expires_at
                    ) VALUES (?, ?, ?, 'POINT_ADJUSTMENT', ?, repeat('d', 64), 201, '{}', 1, ?, ?)
                    """.trimIndent(),
                    id,
                    actorId,
                    accountId,
                    key,
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(90L * 24 * 60 * 60)),
                )
            }

        private fun cleanFinancialRows() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    event_publication,
                    operations_audit_record,
                    loyalty_point_adjustment_command_idempotency,
                    loyalty_point_transaction,
                    loyalty_partial_refund_restoration,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account
                CASCADE
                """.trimIndent(),
            )
        }

        private fun dropFailureTrigger(table: String? = null) {
            if (table != null) {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_point_adjustment_failure ON $table")
                return
            }
            listOf(
                "loyalty_point_account",
                "loyalty_point_lot",
                "loyalty_point_transaction",
                "operations_audit_record",
                "event_publication",
                "loyalty_point_adjustment_command_idempotency",
            ).forEach(::dropFailureTrigger)
        }

        private fun lotAvailable(lotId: UUID): Long = long("SELECT available_amount_krw FROM loyalty_point_lot WHERE id = ?", lotId)

        private fun count(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun long(
            sql: String,
            vararg arguments: Any,
        ): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *arguments)!!

        private fun assertFailure(
            code: FailureCode,
            block: () -> Unit,
        ) {
            assertThatThrownBy(block)
                .isInstanceOfSatisfying(DomainFailure::class.java) { failure ->
                    assertThat(failure.code).isEqualTo(code)
                }
        }

        private companion object {
            val NOW: Instant = Instant.parse("2026-08-04T00:00:00Z")
            val LOT_LOW: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
            val LOT_MIDDLE: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
            val LOT_HIGH: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
            val ADJUSTMENT_CHILD_SOURCE =
                Regex(
                    "^point-adjustment:[0-9a-f-]{36}:lot:[0-9a-f-]{36}${'$'}",
                )
        }
    }
