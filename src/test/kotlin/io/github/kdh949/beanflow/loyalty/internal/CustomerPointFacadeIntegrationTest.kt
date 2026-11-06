package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.BeanflowSharedDatabaseTest
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.internal.OrderCreationDatabaseFixture
import io.github.kdh949.beanflow.shared.api.CustomerOrderDisplayQuery
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
        private val orderDisplays: CustomerOrderDisplayQuery,
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

        @Test
        fun `order accrual context is typed customer scoped and preserves ledger effects and paging`() {
            val customer = UUID.randomUUID()
            val account = insertAccount(customer, 300)
            val order = insertDisplayOrder(customer)
            val reference = OrderCreationDatabaseFixture.registeredReference(order)
            val lot = insertAccrualLot(account, order)
            insertBoundTransaction(account, lot, "ACCRUAL", "CREDIT", Instant.parse("2026-10-03T00:00:00Z"))
            insertBoundTransaction(account, lot, "USE", "DEBIT", Instant.parse("2026-10-02T00:00:00Z"))
            insertTransaction(account, 100, Instant.parse("2026-10-01T00:00:00Z"))
            val body =
                mockMvc
                    .perform(get("/api/v1/me/point-transactions?limit=1").with(customerJwt(customer)))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items[0].orderContext.publicReference").value(reference))
                    .andExpect(jsonPath("$.items[0].orderContext.storeName").value("주문 당시 매장"))
                    .andExpect(jsonPath("$.items[0].orderContext.firstMenuName").value("주문 당시 라떼"))
                    .andExpect(jsonPath("$.items[0].amountKrw").value(100))
                    .andExpect(jsonPath("$.items[0].sourceReference").value("opaque-ACCRUAL-$lot"))
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain(order.toString(), lot.toString().let { "\"pointLotId\":\"$it\"" })
            val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            mockMvc
                .perform(get("/api/v1/me/point-transactions").param("limit", "1").param("cursor", cursor).with(customerJwt(customer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].type").value("USE"))
                .andExpect(jsonPath("$.items[0].amountKrw").value(-100))
                .andExpect(jsonPath("$.items[0].orderContext").doesNotExist())
            mockMvc
                .perform(get("/api/v1/me/point-transactions").with(customerJwt(customer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[2].orderContext").doesNotExist())
            assertThat(orderDisplays.find(UUID.randomUUID(), setOf(order))).isEmpty()
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT available_points_krw FROM loyalty_point_account WHERE id = ?",
                    Long::class.java,
                    account,
                ),
            ).isEqualTo(300)
            assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM loyalty_point_transaction WHERE point_account_id = ?",
                    Long::class.java,
                    account,
                ),
            ).isEqualTo(3)
        }

        @Test
        fun `bound missing or foreign orders fail explicitly without exposing their snapshots`() {
            val customer = UUID.randomUUID()
            val account = insertAccount(customer, 100)
            val foreignOrder = insertDisplayOrder(UUID.randomUUID())
            val lot = insertAccrualLot(account, foreignOrder)
            insertBoundTransaction(account, lot, "ACCRUAL", "CREDIT", Instant.parse("2026-10-03T00:00:00Z"))
            val body =
                mockMvc
                    .perform(get("/api/v1/me/point-transactions").with(customerJwt(customer)))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.items").doesNotExist())
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain(OrderCreationDatabaseFixture.registeredReference(foreignOrder), "주문 당시 매장")
        }

        @Test
        fun `bound order without menu snapshot fails explicitly`() {
            val customer = UUID.randomUUID()
            val account = insertAccount(customer, 100)
            val order = insertDisplayOrder(customer)
            jdbcTemplate.update("DELETE FROM ordering_order_line WHERE order_id = ?", order)
            val lot = insertAccrualLot(account, order)
            insertBoundTransaction(account, lot, "ACCRUAL", "CREDIT", Instant.parse("2026-10-03T00:00:00Z"))
            mockMvc
                .perform(get("/api/v1/me/point-transactions").with(customerJwt(customer)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }

        private fun insertAccrualLot(
            account: UUID,
            order: UUID,
        ): UUID {
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_lot (id, point_account_id, available_amount_krw, reserved_amount_krw,
                    expires_at, issuer_type, issuer_reference, version, accrual_order_id, accrual_source_reference, accrual_snapshot_hash)
                VALUES (?, ?, 100, 0, '2027-01-01T00:00:00Z', 'PLATFORM', 'ordinary', 0, ?, ?, ?)
                """.trimIndent(),
                id,
                account,
                order,
                "accrual:$id",
                "a".repeat(64),
            )
            return id
        }

        private fun insertBoundTransaction(
            account: UUID,
            lot: UUID,
            type: String,
            effect: String,
            occurredAt: Instant,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO loyalty_point_transaction (id, point_account_id, point_lot_id, amount_krw, type, balance_effect, source_reference, occurred_at)
                VALUES (?, ?, ?, 100, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                account,
                lot,
                type,
                effect,
                "opaque-$type-$lot",
                Timestamp.from(occurredAt),
            )
        }

        private fun insertDisplayOrder(customer: UUID): UUID {
            val order = UUID.randomUUID()
            val reference = OrderCreationDatabaseFixture.registerPublicReference(jdbcTemplate, order)
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order (id, customer_id, store_id, pickup_slot_id,
                    public_reference, pickup_business_date, pickup_sequence, store_name_snapshot,
                    pickup_window_start_snapshot, pickup_window_end_snapshot,
                    state, subtotal_krw, coupon_discount_krw, points_applied_krw, payable_krw, currency,
                    paid_at, acceptance_deadline_at, accepted_at, preparing_at, ready_at, completed_at,
                    created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, DATE '2026-10-01', 1, '주문 당시 매장',
                    '2026-10-01T00:00:00Z', '2026-10-01T00:10:00Z',
                    'COMPLETED', 1000, 0, 0, 1000, 'KRW',
                    '2026-10-01T00:00:00Z', '2026-10-01T00:03:00Z', '2026-10-01T00:00:01Z',
                    '2026-10-01T00:00:02Z', '2026-10-01T00:00:03Z', '2026-10-01T00:00:04Z',
                    '2026-10-01T00:00:00Z', '2026-10-01T00:00:04Z', 1)
                """.trimIndent(),
                order,
                customer,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reference,
            )
            jdbcTemplate.update(
                """
                INSERT INTO ordering_order_line (id, order_id, line_sequence, menu_id, menu_name, option_names_json,
                    unit_price_krw, quantity, gross_krw, coupon_discount_krw, points_applied_krw, cash_payable_krw,
                    option_selection_snapshot_state)
                VALUES (?, ?, 0, ?, '주문 당시 라떼', '[]', 1000, 1, 1000, 0, 0, 1000, 'LEGACY_UNAVAILABLE')
                """.trimIndent(),
                UUID.randomUUID(),
                order,
                UUID.randomUUID(),
            )
            return order
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
