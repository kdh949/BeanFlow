package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowSharedDatabaseTest
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
internal class CustomerPointFacadeIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
    ) {
        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    loyalty_point_transaction,
                    loyalty_point_reservation_allocation,
                    loyalty_point_reservation,
                    loyalty_point_lot,
                    loyalty_point_account
                CASCADE
                """.trimIndent(),
            )
        }

        @Test
        fun `actor scoped summary reports the real balance without the account id`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, available = 0)
            insertLot(accountId, available = 0, expiresAt = Instant.parse("2027-01-01T00:00:00Z"))

            mockMvc
                .perform(get("/api/v1/me/points").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.availablePointsKrw").value(0))
                .andExpect(jsonPath("$.recoveryPendingKrw").value(0))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.expiring").isEmpty)
                .andExpect(jsonPath("$.expiringHasMore").value(false))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.pointAccountId").doesNotExist())
        }

        @Test
        fun `expiring lots are reported soonest first and exclude spent lots`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, available = 1_500)
            insertLot(accountId, available = 500, expiresAt = Instant.parse("2027-03-01T00:00:00Z"))
            insertLot(accountId, available = 1_000, expiresAt = Instant.parse("2027-01-01T00:00:00Z"))
            insertLot(accountId, available = 0, expiresAt = Instant.parse("2027-02-01T00:00:00Z"))

            mockMvc
                .perform(get("/api/v1/me/points").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.availablePointsKrw").value(1_500))
                .andExpect(jsonPath("$.expiring.length()").value(2))
                .andExpect(jsonPath("$.expiring[0].amountKrw").value(1_000))
                .andExpect(jsonPath("$.expiring[1].amountKrw").value(500))
                .andExpect(jsonPath("$.expiringHasMore").value(false))
        }

        @Test
        fun `expiring lots beyond the public limit are reported as truncated instead of silently dropped`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, available = 2_100)
            (1..21).forEach { day ->
                insertLot(accountId, available = 100, expiresAt = Instant.parse("2027-01-01T00:00:00Z").plusSeconds(day * 86_400L))
            }

            mockMvc
                .perform(get("/api/v1/me/points").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.expiring.length()").value(20))
                .andExpect(jsonPath("$.expiringHasMore").value(true))
        }

        @Test
        fun `a customer without a point account is an integrity failure and not a zero balance`() {
            mockMvc
                .perform(get("/api/v1/me/points").with(customerJwt(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("POINT_ACCOUNT_INTEGRITY_FAILURE"))
                .andExpect(jsonPath("$.availablePointsKrw").doesNotExist())

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM loyalty_point_account", Long::class.java)).isZero()
        }

        @Test
        fun `the ledger is scoped to the caller and its cursor never leaves the account`() {
            val customerId = UUID.randomUUID()
            val accountId = insertAccount(customerId, available = 300)
            val otherCustomerId = UUID.randomUUID()
            val otherAccountId = insertAccount(otherCustomerId, available = 900)
            insertTransaction(accountId, 100, Instant.parse("2026-08-10T00:00:00Z"))
            insertTransaction(accountId, 200, Instant.parse("2026-08-11T00:00:00Z"))
            insertTransaction(otherAccountId, 900, Instant.parse("2026-08-12T00:00:00Z"))

            val body =
                mockMvc
                    .perform(get("/api/v1/me/point-transactions?limit=1").with(customerJwt(customerId)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].amountKrw").value(200))
                    .andExpect(jsonPath("$.items[0].pointAccountId").doesNotExist())
                    .andReturn()
                    .response.contentAsString
            val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            assertThat(cursor).isNotNull()
            assertThat(body).doesNotContain(accountId.toString())

            mockMvc
                .perform(get("/api/v1/me/point-transactions?limit=1&cursor=$cursor").with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].amountKrw").value(100))

            // The same signed cursor is bound to the issuing account and cannot be replayed by another customer.
            mockMvc
                .perform(get("/api/v1/me/point-transactions?limit=1&cursor=$cursor").with(customerJwt(otherCustomerId)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `the facade requires an authenticated customer`() {
            mockMvc.perform(get("/api/v1/me/points")).andExpect(status().isUnauthorized)
            mockMvc
                .perform(get("/api/v1/me/points").with(merchantJwt(UUID.randomUUID())))
                .andExpect(status().isForbidden)
        }

        private fun insertAccount(
            customerId: UUID,
            available: Long,
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
            }

        private fun insertLot(
            accountId: UUID,
            available: Long,
            expiresAt: Instant,
        ): UUID =
            UUID.randomUUID().also { lotId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO loyalty_point_lot (
                        id, point_account_id, available_amount_krw, reserved_amount_krw,
                        expires_at, issuer_type, issuer_reference, version
                    ) VALUES (?, ?, ?, 0, ?, 'PLATFORM', ?, 0)
                    """.trimIndent(),
                    lotId,
                    accountId,
                    available,
                    Timestamp.from(expiresAt),
                    "customer-point-facade:$lotId",
                )
            }

        private fun insertTransaction(
            accountId: UUID,
            amount: Long,
            occurredAt: Instant,
        ) {
            val lotId = insertLot(accountId, available = 0, expiresAt = occurredAt.plusSeconds(86_400))
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_transaction (
                    id, point_account_id, point_lot_id, amount_krw, type, balance_effect, source_reference, occurred_at
                ) VALUES (?, ?, ?, ?, 'ACCRUAL', 'CREDIT', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                accountId,
                lotId,
                amount,
                "customer-point-facade:${UUID.randomUUID()}",
                Timestamp.from(occurredAt),
            )
        }

        private fun customerJwt(customerId: UUID): RequestPostProcessor =
            jwt()
                .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun merchantJwt(merchantId: UUID): RequestPostProcessor =
            jwt()
                .jwt { it.subject(merchantId.toString()).claim("roles", listOf("MERCHANT")) }
                .authorities(SimpleGrantedAuthority("ROLE_MERCHANT"))
    }
