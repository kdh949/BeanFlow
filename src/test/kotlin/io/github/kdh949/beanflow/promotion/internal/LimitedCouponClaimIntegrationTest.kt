package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies atomic limited coupon claims against PostgreSQL locks and constraints")
@SpringBootTest
internal class LimitedCouponClaimIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbc: JdbcTemplate,
        private val claims: LimitedCouponClaimService,
    ) {
        private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

        @BeforeEach
        fun resetDatabase() {
            jdbc.execute("TRUNCATE TABLE promotion_campaign, merchant_store CASCADE")
        }

        @AfterEach
        fun restoreIssuanceWrites() {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_limited_coupon_issuance ON promotion_coupon_issuance")
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_limited_coupon_issuance()")
        }

        @Test
        fun `same claim command replays one issuance and fixed campaign expiry`() {
            val campaignId = seedCampaign(quota = 3)
            val customerId = UUID.randomUUID()
            val first =
                mockMvc
                    .perform(claimRequest(campaignId, customerId, "claim-command-0001"))
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.campaignId").value(campaignId.toString()))
                    .andExpect(jsonPath("$.couponIssuanceId").isString)
                    .andExpect(jsonPath("$.couponExpiresAt").value(now.plusSeconds(86_400).toString()))
                    .andReturn()
                    .response.contentAsString

            val replay =
                mockMvc
                    .perform(claimRequest(campaignId, customerId, "claim-command-0001"))
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString

            assertThat(replay).isEqualTo(first)
            assertThat(count("promotion_coupon_issuance")).isOne()
            assertThat(count("promotion_limited_coupon_claim")).isOne()
            assertThat(count("promotion_limited_coupon_claim_command")).isOne()
            assertThat(issuedCount(campaignId)).isOne()
            assertThat(
                jdbc.queryForObject(
                    "SELECT coupon_expires_at FROM promotion_coupon_issuance WHERE campaign_id = ?",
                    Instant::class.java,
                    campaignId,
                ),
            ).isEqualTo(now.plusSeconds(86_400))
        }

        @Test
        fun `another key from the same customer is an explicit duplicate without counter growth`() {
            val campaignId = seedCampaign(quota = 3)
            val otherCampaignId = seedCampaign(quota = 3)
            val customerId = UUID.randomUUID()
            mockMvc.perform(claimRequest(campaignId, customerId, "claim-duplicate-01")).andExpect(status().isCreated)

            mockMvc
                .perform(claimRequest(otherCampaignId, customerId, "claim-duplicate-01"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            mockMvc
                .perform(claimRequest(campaignId, customerId, "claim-duplicate-02"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"))

            assertThat(count("promotion_coupon_issuance")).isOne()
            assertThat(issuedCount(campaignId)).isOne()
            assertThat(issuedCount(otherCampaignId)).isZero()
            assertThat(count("promotion_limited_coupon_claim_command")).isEqualTo(2)
        }

        @Test
        fun `two times quota concurrent customers create exactly quota issuances`() {
            val quota = 5
            val campaignId = seedCampaign(quota)
            val ready = CountDownLatch(quota * 2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(quota * 2)
            try {
                val futures =
                    (0 until quota * 2).map { index ->
                        executor.submit(
                            Callable {
                                ready.countDown()
                                start.await(10, TimeUnit.SECONDS)
                                try {
                                    claims.claim(UUID.randomUUID(), campaignId, "claim-race-${index.toString().padStart(4, '0')}", now)
                                    "CREATED"
                                } catch (failure: DomainFailure) {
                                    failure.code.name
                                }
                            },
                        )
                    }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
                start.countDown()
                val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }

                assertThat(outcomes.count { it == "CREATED" }).isEqualTo(quota)
                assertThat(outcomes.count { it == FailureCode.CAMPAIGN_QUOTA_EXHAUSTED.name }).isEqualTo(quota)
                assertThat(count("promotion_coupon_issuance")).isEqualTo(quota)
                assertThat(count("promotion_limited_coupon_claim")).isEqualTo(quota)
                assertThat(issuedCount(campaignId)).isEqualTo(quota)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `issuance failure rolls back claim counter and idempotency response`() {
            val campaignId = seedCampaign(quota = 1)
            jdbc.execute(
                """
                CREATE FUNCTION test_reject_limited_coupon_issuance() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'test issuance rejection'; END
                ${'$'}${'$'}
                """.trimIndent(),
            )
            jdbc.execute(
                """
                CREATE TRIGGER test_reject_limited_coupon_issuance
                BEFORE INSERT ON promotion_coupon_issuance
                FOR EACH ROW EXECUTE FUNCTION test_reject_limited_coupon_issuance()
                """.trimIndent(),
            )

            assertThatThrownBy {
                claims.claim(UUID.randomUUID(), campaignId, "claim-rollback-01", now)
            }.isInstanceOf(DataAccessException::class.java)

            assertThat(count("promotion_coupon_issuance")).isZero()
            assertThat(count("promotion_limited_coupon_claim")).isZero()
            assertThat(count("promotion_limited_coupon_claim_command")).isZero()
            assertThat(issuedCount(campaignId)).isZero()
        }

        private fun claimRequest(
            campaignId: UUID,
            customerId: UUID,
            idempotencyKey: String,
        ) = post("/api/v1/me/events/{campaignId}/claims", campaignId)
            .with(customerJwt(customerId))
            .with(csrf())
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)

        private fun customerJwt(customerId: UUID) =
            jwt()
                .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun seedCampaign(quota: Int): UUID =
            UUID.randomUUID().also { campaignId ->
                val storeId = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)",
                    storeId,
                )
                jdbc.update(
                    """
                    INSERT INTO promotion_campaign (
                        id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                        minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                        cost_bearer, platform_share_bps, store_share_bps, version
                    ) VALUES (?, ?, true, 'FIXED_KRW', 1000, null, 5000, null, true, 'PLATFORM', 10000, 0, 0)
                    """.trimIndent(),
                    campaignId,
                    storeId,
                )
                jdbc.update(
                    """
                    INSERT INTO promotion_limited_campaign (
                        campaign_id, state, title, summary, banner_alt_text, banner_original_key,
                        banner_thumbnail_key, banner_sha256, banner_updated_at, claim_starts_at,
                        claim_ends_at, coupon_expires_at, created_at, updated_at, published_at, stopped_at, version
                    ) VALUES (?, 'PUBLISHED', '선착순 쿠폰', '테스트 혜택', '테스트 배너', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, 1)
                    """.trimIndent(),
                    campaignId,
                    "campaigns/$campaignId/original.jpg",
                    "campaigns/$campaignId/thumbnail.jpg",
                    HASH,
                    Timestamp.from(now.minusSeconds(600)),
                    Timestamp.from(now.minusSeconds(60)),
                    Timestamp.from(now.plusSeconds(3_600)),
                    Timestamp.from(now.plusSeconds(86_400)),
                    Timestamp.from(now.minusSeconds(600)),
                    Timestamp.from(now.minusSeconds(600)),
                    Timestamp.from(now.minusSeconds(60)),
                )
                jdbc.update(
                    "INSERT INTO promotion_limited_campaign_counter (campaign_id, total_quota, issued_count) VALUES (?, ?, 0)",
                    campaignId,
                    quota,
                )
            }

        private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

        private fun issuedCount(campaignId: UUID): Int =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT issued_count FROM promotion_limited_campaign_counter WHERE campaign_id = ?",
                    Int::class.java,
                    campaignId,
                ),
            )

        private companion object {
            const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    }
