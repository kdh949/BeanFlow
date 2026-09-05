package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@BeanflowIsolatedSpringContext("verifies bounded limited coupon command retention against PostgreSQL")
@SpringBootTest
internal class LimitedCouponCommandRetentionIntegrationTest
    @Autowired
    constructor(
        private val repository: LimitedCouponCommandRetentionRepository,
        private val jdbc: JdbcTemplate,
    ) {
        private val now = Instant.parse("2026-09-06T00:00:00Z")

        @BeforeEach
        fun resetDatabase() {
            jdbc.execute("TRUNCATE TABLE promotion_campaign, merchant_store CASCADE")
        }

        @Test
        fun `due rows are deleted at the exact boundary in chunks of one hundred`() {
            val campaignId = seedCampaign()
            repeat(101) { index ->
                seedCampaignCommand(campaignId, index, now)
                seedClaimCommand(campaignId, index, now)
            }
            seedCampaignCommand(campaignId, 999, now.plusNanos(1_000))
            seedClaimCommand(campaignId, 999, now.plusNanos(1_000))

            assertThat(repository.purgeCampaignCommands(now, 100).deletedCount).isEqualTo(100)
            assertThat(repository.purgeClaimCommands(now, 100).deletedCount).isEqualTo(100)
            assertThat(count("promotion_limited_campaign_command")).isEqualTo(2)
            assertThat(count("promotion_limited_coupon_claim_command")).isEqualTo(2)

            assertThat(repository.purgeCampaignCommands(now, 100).deletedCount).isEqualTo(1)
            assertThat(repository.purgeClaimCommands(now, 100).deletedCount).isEqualTo(1)
            assertThat(count("promotion_limited_campaign_command")).isOne()
            assertThat(count("promotion_limited_coupon_claim_command")).isOne()
        }

        private fun seedCampaign(): UUID =
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
                    ) VALUES (?, ?, false, 'FIXED_KRW', 1000, null, 5000, null, true, 'PLATFORM', 10000, 0, 0)
                    """.trimIndent(),
                    campaignId,
                    storeId,
                )
                jdbc.update(
                    """
                    INSERT INTO promotion_limited_campaign (
                        campaign_id, state, title, summary, banner_alt_text,
                        claim_starts_at, claim_ends_at, coupon_expires_at, created_at, updated_at, version
                    ) VALUES (?, 'DRAFT', '보존 테스트', '멱등 명령 보존 테스트', '배너 설명', ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    campaignId,
                    Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(60)),
                    Timestamp.from(now.plusSeconds(120)),
                    Timestamp.from(now),
                    Timestamp.from(now),
                )
                jdbc.update(
                    "INSERT INTO promotion_limited_campaign_counter (campaign_id, total_quota, issued_count) VALUES (?, 1, 0)",
                    campaignId,
                )
            }

        private fun seedCampaignCommand(
            campaignId: UUID,
            index: Int,
            retentionExpiresAt: Instant,
        ) {
            jdbc.update(
                """
                INSERT INTO promotion_limited_campaign_command (
                    id, actor_id, operation, idempotency_key, request_hash, campaign_id,
                    response_json, created_at, retention_expires_at
                ) VALUES (?, ?, 'CREATE_DRAFT', ?, ?, ?, '{}', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "campaign-command-$index",
                HASH,
                campaignId,
                Timestamp.from(now.minusSeconds(90 * 86_400L)),
                Timestamp.from(retentionExpiresAt),
            )
        }

        private fun seedClaimCommand(
            campaignId: UUID,
            index: Int,
            retentionExpiresAt: Instant,
        ) {
            jdbc.update(
                """
                INSERT INTO promotion_limited_coupon_claim_command (
                    id, actor_id, operation, idempotency_key, request_hash, campaign_id,
                    issuance_id, http_status, response_body, created_at, retention_expires_at
                ) VALUES (?, ?, 'LIMITED_COUPON_CLAIM_V1', ?, ?, ?, null, 409, '{}', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "claim-command-$index",
                HASH,
                campaignId,
                Timestamp.from(now.minusSeconds(90 * 86_400L)),
                Timestamp.from(retentionExpiresAt),
            )
        }

        private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

        private companion object {
            const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    }
