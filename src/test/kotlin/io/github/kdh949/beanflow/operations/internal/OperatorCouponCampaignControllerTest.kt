package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.NormalizedStorefrontImageUpload
import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageTarget
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies campaign commands and media pointer commits across transaction boundaries")
@SpringBootTest
internal class OperatorCouponCampaignControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbc: JdbcTemplate,
        private val meterRegistry: MeterRegistry,
    ) {
        @MockitoBean
        private lateinit var storage: StorefrontImageStorageOperations

        private val actorId = UUID.fromString("76000000-0000-0000-0000-000000000001")
        private val jsonMapper = JsonMapper.builder().build()

        @BeforeEach
        fun reset() {
            reset(storage)
            jdbc.update("DELETE FROM promotion_limited_campaign_command")
            jdbc.update("DELETE FROM promotion_limited_coupon_claim_command")
            jdbc.update("DELETE FROM promotion_limited_coupon_claim")
            jdbc.update("DELETE FROM promotion_limited_campaign_counter")
            jdbc.update("DELETE FROM promotion_limited_campaign")
            jdbc.update("DELETE FROM promotion_campaign_eligible_menu")
            jdbc.update("DELETE FROM promotion_coupon_issuance")
            jdbc.update("DELETE FROM promotion_campaign")
            jdbc.update("DELETE FROM operations_operator_permission_grant")
            jdbc.update("DELETE FROM operations_audit_record")
            jdbc.update("DELETE FROM merchant_menu")
            jdbc.update("DELETE FROM merchant_store_discovery_profile")
            jdbc.update("DELETE FROM merchant_store")
            grant("PROMOTION_CAMPAIGN_READ")
            grant("PROMOTION_CAMPAIGN_WRITE")
        }

        @Test
        fun `operator uploads a banner and publishes the draft exactly once`() {
            val storeId = seedStore("빈플로우 잠실")
            val menuId = seedMenu(storeId, "바닐라 라떼")
            val createdBody = create(createBody(storeId, menuId), "campaign-media-create")
            val campaignId = UUID.fromString(jsonMapper.readTree(createdBody).get("campaignId").stringValue())
            stubStorage(campaignId)

            uploadBanner(campaignId, "campaign-banner-01")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.banner.url").value(SIGNED_URL))

            uploadBanner(campaignId, "campaign-banner-01")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(1))
            verify(storage, times(1)).store(StorefrontImageTarget.CAMPAIGN, campaignId, NORMALIZED)

            val publicationBody = """{"expectedVersion":1,"reason":"배너와 혜택 조건 검수 완료"}"""
            repeat(2) {
                mockMvc
                    .perform(
                        post("$BASE/coupon-campaigns/$campaignId/publication")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "campaign-publish-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(publicationBody),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.version").value(2))
            }

            val stoppageBody = """{"expectedVersion":2,"reason":"예산 집행 긴급 중단"}"""
            repeat(2) {
                mockMvc
                    .perform(
                        post("$BASE/coupon-campaigns/$campaignId/stoppage")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "campaign-stop-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(stoppageBody),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.state").value("STOPPED"))
                    .andExpect(jsonPath("$.version").value(3))
            }

            assertThat(jdbc.queryForObject("SELECT active FROM promotion_campaign WHERE id = ?", Boolean::class.java, campaignId)).isTrue()
            assertThat(auditActions()).containsExactly(
                "COUPON_CAMPAIGN_DRAFT_CREATED",
                "COUPON_CAMPAIGN_BANNER_UPDATED",
                "COUPON_CAMPAIGN_PUBLISHED",
                "COUPON_CAMPAIGN_STOPPED",
            )
            assertThat(
                meterRegistry
                    .get("beanflow.promotion.campaign.command")
                    .tag("operation", "stop")
                    .tag("outcome", "succeeded")
                    .counter()
                    .count(),
            ).isGreaterThanOrEqualTo(1.0)
        }

        @Test
        fun `campaign cannot be published without a banner`() {
            val storeId = seedStore("빈플로우 여의도")
            val menuId = seedMenu(storeId, "플랫화이트")
            val createdBody = create(createBody(storeId, menuId), "campaign-no-banner-create")
            val campaignId = jsonMapper.readTree(createdBody).get("campaignId").stringValue()

            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns/$campaignId/publication")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "campaign-no-banner-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"expectedVersion":0,"reason":"게시 사전 검증"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
        }

        @Test
        fun `operator creates and lists a complete draft campaign`() {
            val storeId = seedStore("빈플로우 성수")
            val menuId = seedMenu(storeId, "시그니처 라떼")

            mockMvc
                .perform(get("$BASE/coupon-campaigns/store-options").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].storeId").value(storeId.toString()))
                .andExpect(jsonPath("$[0].name").value("빈플로우 성수"))
            mockMvc
                .perform(get("$BASE/coupon-campaigns/store-options/$storeId/menus").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].menuId").value(menuId.toString()))
                .andExpect(jsonPath("$[0].name").value("시그니처 라떼"))

            val created =
                mockMvc
                    .perform(
                        post("$BASE/coupon-campaigns")
                            .with(operatorJwt())
                            .header("Idempotency-Key", "campaign-create-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(storeId, menuId)),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.state").value("DRAFT"))
                    .andExpect(jsonPath("$.store.name").value("빈플로우 성수"))
                    .andExpect(jsonPath("$.title").value("가을 라떼 선착순 쿠폰"))
                    .andExpect(jsonPath("$.totalQuota").value(100))
                    .andExpect(jsonPath("$.issuedCount").value(0))
                    .andExpect(jsonPath("$.eligibleMenuIds[0]").value(menuId.toString()))
                    .andReturn()

            val campaignId = jsonMapper.readTree(created.response.contentAsString).get("campaignId").stringValue()

            mockMvc
                .perform(get("$BASE/coupon-campaigns/$campaignId").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.campaignId").value(campaignId))
                .andExpect(jsonPath("$.state").value("DRAFT"))

            mockMvc
                .perform(get("$BASE/coupon-campaigns").with(operatorJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].campaignId").value(campaignId))

            assertThat(
                jdbc.queryForObject("SELECT active FROM promotion_campaign WHERE id = ?", Boolean::class.java, UUID.fromString(campaignId)),
            ).isFalse()
            assertThat(auditActions()).containsExactly("COUPON_CAMPAIGN_DRAFT_CREATED")
        }

        @Test
        fun `create replay is stable and a reused key with changed terms is rejected`() {
            val storeId = seedStore("빈플로우 강남")
            val menuId = seedMenu(storeId, "아메리카노")
            val body = createBody(storeId, menuId)

            val first = create(body, "campaign-replay-01")
            val replay = create(body, "campaign-replay-01")
            assertThat(replay).isEqualTo(first)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_limited_campaign", Long::class.java)).isEqualTo(1)
            assertThat(auditActions()).containsExactly("COUPON_CAMPAIGN_DRAFT_CREATED")

            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "campaign-replay-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"totalQuota\":100", "\"totalQuota\":101")),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "campaign-replay-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("가을 프로모션 초안 생성", "다른 운영 사유")),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        }

        @Test
        fun `write grant and valid campaign period are required`() {
            val storeId = seedStore("빈플로우 홍대")
            val menuId = seedMenu(storeId, "콜드브루")
            jdbc.update("DELETE FROM operations_operator_permission_grant WHERE permission = 'PROMOTION_CAMPAIGN_WRITE'")

            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "campaign-denied-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(storeId, menuId)),
                ).andExpect(status().isForbidden)

            grant("PROMOTION_CAMPAIGN_WRITE")
            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns")
                        .with(operatorJwt())
                        .header("Idempotency-Key", "campaign-invalid-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(storeId, menuId).replace("2026-10-10T00:00:00Z", "2026-10-01T00:00:00Z")),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

            assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_limited_campaign", Long::class.java)).isZero()
        }

        private fun create(
            body: String,
            key: String,
        ): String =
            mockMvc
                .perform(
                    post("$BASE/coupon-campaigns")
                        .with(operatorJwt())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString

        private fun createBody(
            storeId: UUID,
            menuId: UUID,
        ) = """
            {
              "storeId":"$storeId",
              "title":"가을 라떼 선착순 쿠폰",
              "summary":"선착순 100명에게 라떼 1,000원 할인",
              "bannerAltText":"노란 배경의 가을 라떼 쿠폰 배너",
              "discount":{"discountType":"FIXED_KRW","fixedAmountKrw":1000},
              "minimumOrderKrw":5000,
              "allMenusEligible":false,
              "eligibleMenuIds":["$menuId"],
              "cost":{"costBearer":"PLATFORM","platformShareBps":10000,"storeShareBps":0},
              "totalQuota":100,
              "claimStartsAt":"2026-10-01T00:00:00Z",
              "claimEndsAt":"2026-10-10T00:00:00Z",
              "couponExpiresAt":"2026-10-31T14:59:59Z",
              "reason":"가을 프로모션 초안 생성"
            }
            """.trimIndent()

        private fun uploadBanner(
            campaignId: UUID,
            key: String,
        ) = mockMvc.perform(
            multipart("$BASE/coupon-campaigns/$campaignId/banner")
                .file(MockMultipartFile("image", "campaign.png", MediaType.IMAGE_PNG_VALUE, byteArrayOf(1, 2, 3)))
                .param("expectedVersion", "0")
                .param("reason", "이벤트 배너 등록")
                .header("Idempotency-Key", key)
                .with { request ->
                    request.method = "PUT"
                    request
                }.with(operatorJwt()),
        )

        private fun stubStorage(campaignId: UUID) {
            `when`(storage.normalizeCampaignBanner(anyValue())).thenReturn(NORMALIZED)
            `when`(storage.store(StorefrontImageTarget.CAMPAIGN, campaignId, NORMALIZED)).thenReturn(PREPARED)
            `when`(storage.access(PREPARED.thumbnailKey)).thenReturn(StorefrontImageAccess(SIGNED_URL, ACCESS_EXPIRES_AT))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            org.mockito.Mockito.any<T>()
            return null as T
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

        private fun seedMenu(
            storeId: UUID,
            name: String,
        ): UUID =
            UUID.randomUUID().also { menuId ->
                jdbc.update(
                    "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available, version) VALUES (?, ?, ?, 5000, true, 0)",
                    menuId,
                    storeId,
                    name,
                )
            }

        private fun grant(permission: String) {
            jdbc.update(
                """
                INSERT INTO operations_operator_permission_grant
                    (actor_id, permission, state, granted_at, revoked_at, version, audit_source_reference)
                VALUES (?, ?, 'ACTIVE', now(), null, 1, ?)
                """.trimIndent(),
                actorId,
                permission,
                "coupon-campaign-test:$permission:${UUID.randomUUID()}",
            )
        }

        private fun auditActions(): List<String> =
            jdbc.query("SELECT action FROM operations_audit_record ORDER BY occurred_at", { row, _ -> row.getString("action") })

        private fun operatorJwt() =
            jwt()
                .jwt { it.subject(actorId.toString()).claim("roles", listOf("PLATFORM_OPERATOR")) }
                .authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

        private companion object {
            const val BASE = "/api/v1/operations"
            const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            const val SIGNED_URL = "https://media.beanflow.test/campaign-signed"
            val ACCESS_EXPIRES_AT: Instant = Instant.parse("2026-09-02T12:15:00Z")
            val NORMALIZED = NormalizedStorefrontImageUpload(byteArrayOf(1), byteArrayOf(1), "image/jpeg", "jpg", HASH)
            val PREPARED = PreparedStorefrontImage("campaigns/id/$HASH/original.jpg", "campaigns/id/$HASH/thumbnail.jpg", HASH)
        }
    }
