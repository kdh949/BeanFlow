package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class PointAccountQueryIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val operatorId = UUID.fromString("20000000-0000-0000-0000-000000000014")

        @BeforeEach
        fun cleanDatabase() {
            dropAuditFailureTrigger()
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    operations_audit_record,
                    operations_operator_permission_grant,
                    loyalty_point_adjustment_command_idempotency,
                    loyalty_point_accrual_result,
                    loyalty_point_recovery_result,
                    loyalty_point_recovery_pending,
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

        @AfterEach
        fun removeFailureTrigger() = dropAuditFailureTrigger()

        @Test
        fun `customer reads only their summary and receives actual recovery pending balance`() {
            val customerId = UUID.fromString("10000000-0000-0000-0000-000000000001")
            val accountId = insertAccount(customerId, available = 310, recoveryPending = 40)
            val otherAccountId = insertAccount(UUID.randomUUID(), available = 20)

            mockMvc
                .perform(get(accountPath(accountId)).with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.availablePointsKrw").value(310))
                .andExpect(jsonPath("$.recoveryPendingKrw").value(40))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.reservedPointsKrw").doesNotExist())
            mockMvc
                .perform(get(accountPath(otherAccountId)).with(customerJwt(customerId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            mockMvc
                .perform(get(accountPath(UUID.randomUUID())).with(customerJwt(customerId)))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            mockMvc.perform(get(accountPath(accountId))).andExpect(status().isUnauthorized)
            mockMvc
                .perform(get(transactionPath(accountId)).with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
        }

        @Test
        fun `store settlement and malformed customer subjects cannot read point accounts`() {
            val accountId = insertAccount(UUID.randomUUID(), available = 10)
            listOf("STORE_OWNER", "SETTLEMENT_OPERATOR").forEach { role ->
                mockMvc
                    .perform(get(accountPath(accountId)).with(roleJwt(UUID.randomUUID().toString(), role)))
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            }
            mockMvc
                .perform(get(accountPath(accountId)).with(roleJwt("not-a-uuid", "CUSTOMER")))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        }

        @Test
        fun `customer ledger projects signed effects in stable newest first tuple order`() {
            val customerId = UUID.fromString("10000000-0000-0000-0000-000000000002")
            val accountId = insertAccount(customerId, available = 100)
            val occurredAt = Instant.parse("2026-08-06T02:00:00Z")
            insertTransaction(
                accountId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "USE",
                "DEBIT",
                10,
                occurredAt.minusSeconds(1),
            )
            insertTransaction(
                accountId,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "ACCRUAL",
                "CREDIT",
                20,
                occurredAt,
            )
            insertTransaction(
                accountId,
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "USE",
                "DEBIT",
                20,
                occurredAt,
            )
            insertTransaction(
                accountId,
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "ADJUSTMENT",
                "CREDIT",
                30,
                occurredAt.plusSeconds(1),
            )

            mockMvc
                .perform(get(transactionPath(accountId)).param("limit", "20").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].transactionId").value("00000000-0000-0000-0000-000000000004"))
                .andExpect(jsonPath("$.items[0].amountKrw").value(30))
                .andExpect(jsonPath("$.items[1].transactionId").value("00000000-0000-0000-0000-000000000003"))
                .andExpect(jsonPath("$.items[1].amountKrw").value(-20))
                .andExpect(jsonPath("$.items[2].transactionId").value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.items[2].amountKrw").value(20))
                .andExpect(jsonPath("$.items[3].amountKrw").value(-10))
                .andExpect(jsonPath("$.items[0].pointLotId").doesNotExist())
                .andExpect(jsonPath("$.items[0].balanceEffect").doesNotExist())
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
        }

        @Test
        fun `ledger defaults to twenty pages by signed account scope and rejects invalid limits and cursors`() {
            val customerId = UUID.fromString("10000000-0000-0000-0000-000000000003")
            val accountId = insertAccount(customerId, available = 100)
            val otherAccountId = insertAccount(customerId = UUID.randomUUID(), available = 100)
            repeat(21) { index ->
                insertTransaction(
                    accountId,
                    UUID.nameUUIDFromBytes("transaction-$index".toByteArray()),
                    "ACCRUAL",
                    "CREDIT",
                    1,
                    Instant.parse("2026-08-06T00:00:00Z").plusSeconds(index.toLong()),
                )
            }

            val first =
                mockMvc
                    .perform(get(transactionPath(accountId)).with(customerJwt(customerId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(20))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val cursor = nextCursor(first.response.contentAsString)
            insertTransaction(
                accountId,
                UUID.randomUUID(),
                "ACCRUAL",
                "CREDIT",
                1,
                Instant.parse("2026-08-07T00:00:00Z"),
            )
            val second =
                mockMvc
                    .perform(
                        get(transactionPath(accountId))
                            .param("limit", "1")
                            .param("cursor", cursor)
                            .with(customerJwt(customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
                    .andReturn()
            assertThat(transactionIds(second.response.contentAsString))
                .doesNotContainAnyElementsOf(transactionIds(first.response.contentAsString))
            mockMvc
                .perform(get(transactionPath(accountId)).param("limit", "100").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(22))
            mockMvc
                .perform(
                    get(transactionPath(otherAccountId))
                        .param("cursor", cursor)
                        .with(customerJwt(otherAccountId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get(transactionPath(accountId))
                        .param("cursor", cursor.dropLast(1) + if (cursor.last() == 'a') "b" else "a")
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            listOf("0", "101").forEach { limit ->
                mockMvc
                    .perform(get(transactionPath(accountId)).param("limit", limit).with(customerJwt(customerId)))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            }
            mockMvc
                .perform(
                    get(transactionPath(accountId))
                        .param("cursor", "x".repeat(2048))
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `operator requires active grant and reason then commits exactly one audit with the result`() {
            val customerId = UUID.fromString("10000000-0000-0000-0000-000000000004")
            val accountId = insertAccount(customerId, available = 50)

            mockMvc
                .perform(get(accountPath(accountId)).header("X-Access-Reason", "Support request").with(operatorJwt(operatorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            grant(operatorId, "POINT_ADJUSTMENT")
            mockMvc
                .perform(get(accountPath(accountId)).header("X-Access-Reason", "Support request").with(operatorJwt(operatorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            grant(operatorId)
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = ? " +
                    "WHERE actor_id = ? AND permission = 'POINT_ACCOUNT_READ'",
                Timestamp.from(Instant.parse("2026-08-06T00:01:00Z")),
                operatorId,
            )
            mockMvc
                .perform(get(accountPath(accountId)).header("X-Access-Reason", "Support request").with(operatorJwt(operatorId)))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            jdbcTemplate.update(
                "UPDATE operations_operator_permission_grant SET state = 'ACTIVE', revoked_at = NULL " +
                    "WHERE actor_id = ? AND permission = 'POINT_ACCOUNT_READ'",
                operatorId,
            )
            listOf(null, "   ").forEach { reason ->
                val request = get(accountPath(accountId)).with(operatorJwt(operatorId))
                if (reason != null) request.header("X-Access-Reason", reason)
                mockMvc
                    .perform(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                assertThat(count("operations_audit_record")).isZero()
            }
            mockMvc
                .perform(get(accountPath(accountId)).header("X-Access-Reason", "Customer balance support").with(operatorJwt(operatorId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
            assertThat(count("operations_audit_record")).isOne()
            val audit = jdbcTemplate.queryForMap("SELECT * FROM operations_audit_record")
            assertThat(audit["action"]).isEqualTo("POINT_ACCOUNT_READ")
            assertThat(audit["target_type"]).isEqualTo("POINT_ACCOUNT")
            assertThat(audit["target_id"]).isEqualTo(accountId)
            assertThat(audit["reason"]).isEqualTo("Customer balance support")

            jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION test_reject_point_account_audit()
                RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'injected audit failure'; END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER test_point_account_audit_failure BEFORE INSERT ON operations_audit_record " +
                    "FOR EACH ROW EXECUTE FUNCTION test_reject_point_account_audit()",
            )
            mockMvc
                .perform(
                    get(transactionPath(accountId))
                        .header("X-Access-Reason", "Ledger support")
                        .with(operatorJwt(operatorId)),
                ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            assertThat(count("operations_audit_record")).isOne()
            val meterTagValues =
                meterRegistry
                    .find("beanflow.loyalty.point_account.read.count")
                    .meters()
                    .flatMap { meter -> meter.id.tags.map { it.value } }
            assertThat(meterTagValues).doesNotContain(operatorId.toString(), accountId.toString(), "Customer balance support")
        }

        private fun insertAccount(
            customerId: UUID,
            available: Long,
            recoveryPending: Long = 0,
        ): UUID =
            UUID.randomUUID().also { accountId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_account (
                        id, customer_id, available_points_krw, reserved_points_krw, recovery_pending_krw, version
                    ) VALUES (?, ?, ?, 0, 0, 0)
                    """.trimIndent(),
                    accountId,
                    customerId,
                    available,
                )
                if (recoveryPending > 0) {
                    transactions.executeWithoutResult {
                        jdbcTemplate.update(
                            """
                            INSERT INTO loyalty_point_recovery_pending (
                                id, point_account_id, refund_source_reference, initial_amount_krw,
                                remaining_amount_krw, state, created_at
                            ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                            """.trimIndent(),
                            UUID.randomUUID(),
                            accountId,
                            "recovery:${UUID.randomUUID()}",
                            recoveryPending,
                            recoveryPending,
                            Timestamp.from(Instant.parse("2026-08-06T00:00:00Z")),
                        )
                        jdbcTemplate.update(
                            "UPDATE loyalty_point_account SET recovery_pending_krw = ? WHERE id = ?",
                            recoveryPending,
                            accountId,
                        )
                    }
                }
            }

        private fun insertTransaction(
            accountId: UUID,
            transactionId: UUID,
            type: String,
            balanceEffect: String,
            amount: Long,
            occurredAt: Instant,
        ) {
            val lotId =
                UUID.randomUUID().also {
                    jdbcTemplate.update(
                        """
                        INSERT INTO loyalty_point_lot (
                            id, point_account_id, available_amount_krw, reserved_amount_krw,
                            expires_at, issuer_type, issuer_reference, version
                        ) VALUES (?, ?, 0, 0, ?, 'PLATFORM', ?, 0)
                        """.trimIndent(),
                        it,
                        accountId,
                        Timestamp.from(occurredAt.plusSeconds(86_400)),
                        "query-test:$it",
                    )
                }
            val source =
                if (type == "ADJUSTMENT") {
                    "point-adjustment:${UUID.randomUUID()}:lot:$lotId"
                } else {
                    "query:$type:${UUID.randomUUID()}"
                }
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_transaction (
                    id, point_account_id, point_lot_id, amount_krw, type, balance_effect, source_reference, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                transactionId,
                accountId,
                lotId,
                amount,
                type,
                balanceEffect,
                source,
                Timestamp.from(occurredAt),
            )
        }

        private fun grant(
            actorId: UUID,
            permission: String = "POINT_ACCOUNT_READ",
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO operations_operator_permission_grant (
                    actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference
                ) VALUES (?, ?, 'ACTIVE', ?, NULL, 1, ?)
                """.trimIndent(),
                actorId,
                permission,
                Timestamp.from(Instant.parse("2026-08-06T00:00:00Z")),
                "test:point-account-read-grant:$actorId:$permission",
            )
        }

        private fun customerJwt(customerId: UUID): RequestPostProcessor =
            jwt()
                .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun operatorJwt(actorId: UUID): RequestPostProcessor = roleJwt(actorId.toString(), "PLATFORM_OPERATOR")

        private fun roleJwt(
            subject: String,
            role: String,
        ): RequestPostProcessor =
            jwt()
                .jwt { it.subject(subject).claim("roles", listOf(role)) }
                .authorities(SimpleGrantedAuthority("ROLE_$role"))

        private fun accountPath(accountId: UUID): String = "/api/v1/point-accounts/$accountId"

        private fun transactionPath(accountId: UUID): String = "${accountPath(accountId)}/transactions"

        private fun nextCursor(body: String): String =
            Regex("\\\"nextCursor\\\":\\\"([^\\\"]+)\\\"")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?: error("nextCursor is missing from response: $body")

        private fun transactionIds(body: String): List<String> =
            Regex("\\\"transactionId\\\":\\\"([^\\\"]+)\\\"")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()

        private fun count(table: String): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun dropAuditFailureTrigger() {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_point_account_audit_failure ON operations_audit_record")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_reject_point_account_audit()")
        }
    }
