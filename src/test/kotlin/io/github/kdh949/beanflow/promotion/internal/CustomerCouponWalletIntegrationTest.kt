package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.promotion.api.RestoreCouponAfterTerminationCommand
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, CustomerCouponWalletTestClockConfiguration::class)
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
internal class CustomerCouponWalletIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbcTemplate: JdbcTemplate,
        private val couponOperations: CouponReservationOperations,
        private val clock: CustomerCouponWalletMutableClock,
        transactionManager: PlatformTransactionManager,
    ) {
        private val transactions = TransactionTemplate(transactionManager)
        private val now = Instant.parse("2026-08-18T03:00:00Z")

        @BeforeEach
        fun cleanDatabase() {
            jdbcTemplate.execute(
                "TRUNCATE TABLE promotion_compensation_coupon_eligible_menu, " +
                    "promotion_compensation_coupon_terms_snapshot, promotion_coupon_reservation, " +
                    "promotion_coupon_issuance, promotion_campaign_eligible_menu, promotion_campaign CASCADE",
            )
            clock.set(now)
        }

        @AfterEach
        fun restoreCouponIssuanceTriggers() {
            jdbcTemplate.execute("ALTER TABLE promotion_coupon_issuance ENABLE TRIGGER USER")
        }

        @Test
        fun `wallet returns only the actor owned eligible coupons and keeps another store coupon visible`() {
            val customerId = UUID.randomUUID()
            val requestedStoreId = UUID.randomUUID()
            val applicable = insertCoupon(customerId, requestedStoreId, expiresAt = now.plusSeconds(3_600))
            val otherStore = insertCoupon(customerId, UUID.randomUUID(), expiresAt = now.plusSeconds(7_200))
            val restoredOriginal =
                insertCoupon(
                    customerId,
                    requestedStoreId,
                    state = CouponIssuanceState.RESTORED,
                    expiresAt = now.plusSeconds(10_800),
                )
            insertCoupon(customerId, requestedStoreId, active = false, expiresAt = now.plusSeconds(14_400))
            insertCoupon(customerId, requestedStoreId, expiresAt = now)
            insertCoupon(customerId, requestedStoreId, expiresAt = now.minusSeconds(1))
            insertCoupon(UUID.randomUUID(), requestedStoreId, expiresAt = now.plusSeconds(18_000))
            insertCoupon(customerId, requestedStoreId, state = CouponIssuanceState.RESERVED, expiresAt = now.plusSeconds(21_600))

            mockMvc
                .perform(get("/api/v1/me/coupons").param("storeId", requestedStoreId.toString()).with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].couponIssuanceId").value(applicable.issuanceId.toString()))
                .andExpect(jsonPath("$.items[0].benefit.discountType").value("FIXED_KRW"))
                .andExpect(jsonPath("$.items[0].benefit.fixedAmountKrw").value(500))
                .andExpect(jsonPath("$.items[0].minimumOrderKrw").value(2_000))
                .andExpect(jsonPath("$.items[0].applicable").value(true))
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.items[1].couponIssuanceId").value(otherStore.issuanceId.toString()))
                .andExpect(jsonPath("$.items[1].applicable").value(false))
                .andExpect(jsonPath("$.items[1].reasonCode").value("STORE_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.items[2].couponIssuanceId").value(restoredOriginal.issuanceId.toString()))
                .andExpect(jsonPath("$.items[*].campaignId").doesNotExist())
                .andExpect(jsonPath("$.items[*].customerId").doesNotExist())
        }

        @Test
        fun `wallet compensation coupon uses immutable terms after its campaign is inactive`() {
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val original = insertCoupon(customerId, storeId, expiresAt = now.plusSeconds(60))
            val orderId = UUID.randomUUID()
            clock.set(now.minusSeconds(120))
            transactions.executeWithoutResult {
                couponOperations.reserve(
                    ReserveCouponCommand(
                        orderId = orderId,
                        customerId = customerId,
                        storeId = storeId,
                        couponIssuanceId = original.issuanceId,
                        lines = listOf(CouponPricingLine(0, UUID.randomUUID(), 5_000)),
                        reservationExpiresAt = now.minusSeconds(60),
                        sourceReference = "wallet-compensation-reserve-$orderId",
                    ),
                )
                couponOperations.confirm(orderId, "wallet-compensation-reserve-$orderId")
            }
            couponOperations.restoreUsedAfterTermination(
                RestoreCouponAfterTerminationCommand(
                    orderId = orderId,
                    terminatedAt = now.plusSeconds(120),
                    sourceReference = "wallet-compensation-restore-$orderId",
                    trigger = OrderTerminationTrigger.CUSTOMER_CANCELLATION,
                    policyVersionId = 1,
                    mode = ExpiredCouponRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE,
                    compensationValidityDays = 30,
                ),
            )
            val compensationId =
                jdbcTemplate.queryForObject(
                    "SELECT id FROM promotion_coupon_issuance WHERE original_issuance_id = ?",
                    UUID::class.java,
                    original.issuanceId,
                )!!
            jdbcTemplate.update("UPDATE promotion_campaign SET active = false WHERE id = ?", original.campaignId)
            clock.set(now.plusSeconds(180))

            mockMvc
                .perform(get("/api/v1/me/coupons").param("storeId", storeId.toString()).with(customerJwt(customerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].couponIssuanceId").value(compensationId.toString()))
                .andExpect(jsonPath("$.items[0].benefit.fixedAmountKrw").value(500))
                .andExpect(jsonPath("$.items[0].applicable").value(true))
        }

        @Test
        fun `wallet cursor is actor and store scoped and rejects malformed paging before a query`() {
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val couponIds =
                (1L..3L).map { index ->
                    insertCoupon(customerId, storeId, expiresAt = now.plusSeconds(index * 3_600)).issuanceId
                }

            val first =
                mockMvc
                    .perform(
                        get("/api/v1/me/coupons")
                            .param("storeId", storeId.toString())
                            .param("limit", "1")
                            .with(customerJwt(customerId)),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].couponIssuanceId").value(couponIds.first().toString()))
                    .andExpect(jsonPath("$.page.nextCursor").isString)
                    .andReturn()
            val cursor =
                checkNotNull(
                    Regex("\\\"nextCursor\\\":\\\"([^\\\"]+)\\\"").find(first.response.contentAsString)?.groupValues?.get(1),
                )

            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", storeId.toString())
                        .param("cursor", cursor)
                        .param("limit", "100")
                        .with(customerJwt(customerId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].couponIssuanceId").value(couponIds[1].toString()))
                .andExpect(jsonPath("$.items[1].couponIssuanceId").value(couponIds[2].toString()))
            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", storeId.toString())
                        .param("cursor", "malformed")
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", storeId.toString())
                        .param("cursor", cursor)
                        .with(customerJwt(UUID.randomUUID())),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            clock.set(now.plusSeconds(86_400))
            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", storeId.toString())
                        .param("cursor", cursor)
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", UUID.randomUUID().toString())
                        .param("cursor", cursor)
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            mockMvc
                .perform(
                    get("/api/v1/me/coupons")
                        .param("storeId", storeId.toString())
                        .param("limit", "0")
                        .with(customerJwt(customerId)),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        fun `wallet reports a compensation snapshot integrity failure instead of an empty page`() {
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            val campaignId = UUID.randomUUID()
            val originalIssuanceId = UUID.randomUUID()
            val compensationIssuanceId = UUID.randomUUID()
            insertCampaign(campaignId, storeId, active = false)
            jdbcTemplate.execute("ALTER TABLE promotion_coupon_issuance DISABLE TRIGGER USER")
            jdbcTemplate.update(
                """
                INSERT INTO promotion_coupon_issuance (
                    id, campaign_id, customer_id, state, coupon_expires_at, original_issuance_id,
                    restoration_source_reference, restoration_trigger, restoration_policy_version_id, version
                ) VALUES (?, ?, ?, 'AVAILABLE', ?, NULL, NULL, NULL, NULL, 0),
                         (?, ?, ?, 'RESTORED', ?, ?, ?, 'CUSTOMER_CANCELLATION', 1, 0)
                """.trimIndent(),
                originalIssuanceId,
                campaignId,
                customerId,
                Timestamp.from(now.plusSeconds(3_600)),
                compensationIssuanceId,
                campaignId,
                customerId,
                Timestamp.from(now.plusSeconds(7_200)),
                originalIssuanceId,
                "wallet-invalid-snapshot-$compensationIssuanceId",
            )
            jdbcTemplate.execute("ALTER TABLE promotion_coupon_issuance ENABLE TRIGGER USER")

            mockMvc
                .perform(get("/api/v1/me/coupons").param("storeId", storeId.toString()).with(customerJwt(customerId)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("SETTLEMENT_INPUT_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }

        @Test
        fun `wallet projection query records an executable explain plan without assuming an index`() {
            val customerId = UUID.randomUUID()
            val storeId = UUID.randomUUID()
            insertCoupon(customerId, storeId, expiresAt = now.plusSeconds(3_600))

            val plan =
                jdbcTemplate.query(
                    """
                    EXPLAIN (ANALYZE, BUFFERS)
                    SELECT ci.id
                      FROM promotion_coupon_issuance ci
                      LEFT JOIN promotion_campaign campaign ON campaign.id = ci.campaign_id
                      LEFT JOIN promotion_compensation_coupon_terms_snapshot snapshot
                        ON snapshot.coupon_issuance_id = ci.id
                     WHERE ci.customer_id = ?
                       AND ci.state IN ('AVAILABLE', 'RESTORED')
                       AND ci.coupon_expires_at > ?
                       AND (ci.original_issuance_id IS NOT NULL OR campaign.active = true)
                     ORDER BY ci.coupon_expires_at ASC, ci.id ASC
                     LIMIT ?
                    """.trimIndent(),
                    { resultSet, _ -> resultSet.getString(1) },
                    customerId,
                    Timestamp.from(now),
                    21,
                )

            assertThat(plan).isNotEmpty().anySatisfy { line -> assertThat(line).contains("Execution Time") }
            println("Customer coupon wallet EXPLAIN (ANALYZE, BUFFERS):\\n${plan.joinToString("\\n")}")
        }

        @Test
        fun `wallet requires customer authentication and a store id`() {
            mockMvc.perform(get("/api/v1/me/coupons")).andExpect(status().isUnauthorized)
            mockMvc
                .perform(get("/api/v1/me/coupons").with(merchantJwt(UUID.randomUUID())))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(get("/api/v1/me/coupons").with(customerJwt(UUID.randomUUID())))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        private fun insertCoupon(
            customerId: UUID,
            storeId: UUID,
            active: Boolean = true,
            state: CouponIssuanceState = CouponIssuanceState.AVAILABLE,
            expiresAt: Instant,
        ): CouponFixture {
            val campaignId = UUID.randomUUID()
            val issuanceId = UUID.randomUUID()
            insertCampaign(campaignId, storeId, active)
            jdbcTemplate.update(
                """
                INSERT INTO promotion_coupon_issuance (
                    id, campaign_id, customer_id, state, coupon_expires_at, reserved_order_id,
                    original_issuance_id, restoration_source_reference, restoration_trigger,
                    restoration_policy_version_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
                issuanceId,
                campaignId,
                customerId,
                state.name,
                Timestamp.from(expiresAt),
                if (state in setOf(CouponIssuanceState.RESERVED, CouponIssuanceState.USED)) UUID.randomUUID() else null,
            )
            return CouponFixture(campaignId, issuanceId)
        }

        private fun insertCampaign(
            campaignId: UUID,
            storeId: UUID,
            active: Boolean,
        ) {
            jdbcTemplate.update(
                """
                INSERT INTO promotion_campaign (
                    id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                    minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                    cost_bearer, platform_share_bps, store_share_bps, version
                ) VALUES (?, ?, ?, 'FIXED_KRW', 500, NULL, 2000, NULL, true, 'STORE', 0, 10000, 0)
                """.trimIndent(),
                campaignId,
                storeId,
                active,
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

        private data class CouponFixture(
            val campaignId: UUID,
            val issuanceId: UUID,
        )
    }

@TestConfiguration(proxyBeanMethods = false)
internal class CustomerCouponWalletTestClockConfiguration {
    @Bean
    @Primary
    fun customerCouponWalletClock(): CustomerCouponWalletMutableClock =
        CustomerCouponWalletMutableClock(Instant.parse("2026-08-18T03:00:00Z"))
}

internal class CustomerCouponWalletMutableClock(
    initial: Instant,
) : Clock() {
    private val current = AtomicReference(initial)

    fun set(value: Instant) {
        current.set(value)
    }

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = current.get()
}
