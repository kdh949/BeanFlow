package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies customer event availability without external media calls inside the database transaction")
@SpringBootTest
internal class CustomerEventCampaignControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbc: JdbcTemplate,
    ) {
        @MockitoBean
        private lateinit var storage: StorefrontImageStorageOperations

        private val customerId = UUID.fromString("77000000-0000-0000-0000-000000000001")
        private val now = Instant.now()

        @BeforeEach
        fun resetDatabase() {
            reset(storage)
            jdbc.execute("TRUNCATE TABLE promotion_campaign, merchant_store CASCADE")
        }

        @Test
        fun `customer sees only published open campaigns with remaining quota`() {
            val storeId = seedStore("빈플로우 성수")
            val visible = seedCampaign(storeId, "가을 라떼 쿠폰", true, "PUBLISHED", now.minusSeconds(60), now.plusSeconds(3_600), 100, 7)
            seedCampaign(storeId, "아직 열리지 않은 쿠폰", true, "PUBLISHED", now.plusSeconds(60), now.plusSeconds(3_600), 100, 0)
            seedCampaign(storeId, "수량이 끝난 쿠폰", true, "PUBLISHED", now.minusSeconds(60), now.plusSeconds(3_600), 100, 100)
            seedCampaign(storeId, "중단된 쿠폰", false, "STOPPED", now.minusSeconds(60), now.plusSeconds(3_600), 100, 1)
            `when`(storage.access(anyString())).thenReturn(StorefrontImageAccess(SIGNED_URL, now.plusSeconds(900)))

            mockMvc
                .perform(get("/api/v1/me/events").with(customerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].campaignId").value(visible.toString()))
                .andExpect(jsonPath("$[0].store.name").value("빈플로우 성수"))
                .andExpect(jsonPath("$[0].title").value("가을 라떼 쿠폰"))
                .andExpect(jsonPath("$[0].banner.url").value(SIGNED_URL))
                .andExpect(jsonPath("$[0].benefit.discountType").value("FIXED_KRW"))
                .andExpect(jsonPath("$[0].benefit.fixedAmountKrw").value(1_000))
                .andExpect(jsonPath("$[0].remainingCount").value(93))
                .andExpect(jsonPath("$[0].bannerThumbnailKey").doesNotExist())
            verify(storage, times(1)).access("campaigns/$visible/thumbnail.jpg")
        }

        @Test
        fun `event list requires a customer actor`() {
            mockMvc.perform(get("/api/v1/me/events")).andExpect(status().isUnauthorized)
        }

        private fun seedStore(name: String): UUID =
            UUID.randomUUID().also { storeId ->
                jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
                jdbc.update(
                    "INSERT INTO merchant_store_discovery_profile (store_id, name, location, region_code) " +
                        "VALUES (?, ?, ST_GeogFromText('SRID=4326;POINT(127.0276 37.4979)'), '1168000000')",
                    storeId,
                    name,
                )
            }

        private fun seedCampaign(
            storeId: UUID,
            title: String,
            active: Boolean,
            state: String,
            startsAt: Instant,
            endsAt: Instant,
            quota: Int,
            issued: Int,
        ): UUID =
            UUID.randomUUID().also { campaignId ->
                jdbc.update(
                    """
                    INSERT INTO promotion_campaign (
                        id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                        minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                        cost_bearer, platform_share_bps, store_share_bps, version
                    ) VALUES (?, ?, ?, 'FIXED_KRW', 1000, null, 5000, null, true, 'PLATFORM', 10000, 0, 0)
                    """.trimIndent(),
                    campaignId,
                    storeId,
                    active,
                )
                val publishedAt = if (state == "DRAFT") null else Timestamp.from(now.minusSeconds(120))
                val stoppedAt = if (state == "STOPPED") Timestamp.from(now.minusSeconds(30)) else null
                jdbc.update(
                    """
                    INSERT INTO promotion_limited_campaign (
                        campaign_id, state, title, summary, banner_alt_text, banner_original_key,
                        banner_thumbnail_key, banner_sha256, banner_updated_at, claim_starts_at,
                        claim_ends_at, coupon_expires_at, created_at, updated_at, published_at, stopped_at, version
                    ) VALUES (?, ?, ?, '선착순 혜택', '노란 배경의 라떼 쿠폰 배너', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """.trimIndent(),
                    campaignId,
                    state,
                    title,
                    "campaigns/$campaignId/original.jpg",
                    "campaigns/$campaignId/thumbnail.jpg",
                    HASH,
                    Timestamp.from(now.minusSeconds(180)),
                    Timestamp.from(startsAt),
                    Timestamp.from(endsAt),
                    Timestamp.from(endsAt.plusSeconds(86_400)),
                    Timestamp.from(now.minusSeconds(300)),
                    Timestamp.from(now),
                    publishedAt,
                    stoppedAt,
                )
                jdbc.update(
                    "INSERT INTO promotion_limited_campaign_counter (campaign_id, total_quota, issued_count) VALUES (?, ?, ?)",
                    campaignId,
                    quota,
                    issued,
                )
            }

        private fun customerJwt() =
            jwt()
                .jwt { it.subject(customerId.toString()).claim("roles", listOf("CUSTOMER")) }
                .authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private companion object {
            const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            const val SIGNED_URL = "https://media.beanflow.test/campaign-signed"
        }
    }
