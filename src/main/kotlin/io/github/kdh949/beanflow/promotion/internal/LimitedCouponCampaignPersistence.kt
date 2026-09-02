package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CreateLimitedCouponCampaignDraftCommand
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignBannerPointer
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignCost
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignDiscount
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignSnapshot
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignState
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class LimitedCampaignCommandRecord(
    val requestHash: String,
    val campaignId: UUID,
    val responseJson: String,
)

@Component
internal class LimitedCouponCampaignPersistence(
    private val jdbc: JdbcTemplate,
    private val identifiers: IdentifierSource,
) {
    fun lockCommand(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))",
            { _, _ -> Unit },
            "$actorId:$operation:$idempotencyKey",
        )
    }

    fun findCommand(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): LimitedCampaignCommandRecord? =
        jdbc
            .query(
                """
                SELECT request_hash, campaign_id, response_json
                  FROM promotion_limited_campaign_command
                 WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                """.trimIndent(),
                { row, _ ->
                    LimitedCampaignCommandRecord(
                        row.getString("request_hash"),
                        row.getObject("campaign_id", UUID::class.java),
                        row.getString("response_json"),
                    )
                },
                actorId,
                operation,
                idempotencyKey,
            ).singleOrNull()

    fun createDraft(command: CreateLimitedCouponCampaignDraftCommand): LimitedCouponCampaignSnapshot {
        val campaignId = identifiers.next()
        jdbc.update(
            """
            INSERT INTO promotion_campaign (
                id, store_id, active, discount_type, fixed_amount_krw, rate_bps,
                minimum_eligible_subtotal_krw, maximum_discount_krw, all_menus_eligible,
                cost_bearer, platform_share_bps, store_share_bps, version
            ) VALUES (?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            campaignId,
            command.storeId,
            command.discount.discountType.name,
            command.discount.fixedAmountKrw,
            command.discount.rateBps,
            command.minimumOrderKrw,
            command.discount.maximumDiscountKrw,
            command.allMenusEligible,
            command.cost.costBearer.name,
            command.cost.platformShareBps,
            command.cost.storeShareBps,
        )
        command.eligibleMenuIds.sorted().forEach { menuId ->
            jdbc.update(
                "INSERT INTO promotion_campaign_eligible_menu (id, campaign_id, menu_id) VALUES (?, ?, ?)",
                identifiers.next(),
                campaignId,
                menuId,
            )
        }
        jdbc.update(
            """
            INSERT INTO promotion_limited_campaign (
                campaign_id, state, title, summary, banner_alt_text,
                claim_starts_at, claim_ends_at, coupon_expires_at,
                created_at, updated_at, version
            ) VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            campaignId,
            command.title,
            command.summary,
            command.bannerAltText,
            Timestamp.from(command.claimStartsAt),
            Timestamp.from(command.claimEndsAt),
            Timestamp.from(command.couponExpiresAt),
            Timestamp.from(command.now),
            Timestamp.from(command.now),
        )
        jdbc.update(
            "INSERT INTO promotion_limited_campaign_counter (campaign_id, total_quota, issued_count) VALUES (?, ?, 0)",
            campaignId,
            command.totalQuota,
        )
        return requireNotNull(find(campaignId))
    }

    fun saveCommand(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
        requestHash: String,
        campaignId: UUID,
        responseJson: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO promotion_limited_campaign_command (
                id, actor_id, operation, idempotency_key, request_hash, campaign_id,
                response_json, created_at, retention_expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            identifiers.next(),
            actorId,
            operation,
            idempotencyKey,
            requestHash,
            campaignId,
            responseJson,
            Timestamp.from(now),
            Timestamp.from(now.plus(COMMAND_RETENTION)),
        )
    }

    fun lockCampaign(campaignId: UUID): LimitedCouponCampaignSnapshot? {
        jdbc.query("SELECT campaign_id FROM promotion_limited_campaign WHERE campaign_id = ? FOR UPDATE", { _, _ -> Unit }, campaignId)
        return find(campaignId)
    }

    fun replaceBanner(
        campaignId: UUID,
        prepared: PreparedStorefrontImage,
        now: Instant,
    ): LimitedCouponCampaignSnapshot {
        jdbc.update(
            """
            UPDATE promotion_limited_campaign
               SET banner_original_key = ?, banner_thumbnail_key = ?, banner_sha256 = ?,
                   banner_updated_at = ?, updated_at = ?, version = version + 1
             WHERE campaign_id = ?
            """.trimIndent(),
            prepared.originalKey,
            prepared.thumbnailKey,
            prepared.sha256,
            Timestamp.from(now),
            Timestamp.from(now),
            campaignId,
        )
        return requireNotNull(find(campaignId))
    }

    fun publish(
        campaignId: UUID,
        now: Instant,
    ): LimitedCouponCampaignSnapshot {
        jdbc.update("UPDATE promotion_campaign SET active = true, version = version + 1 WHERE id = ?", campaignId)
        jdbc.update(
            """
            UPDATE promotion_limited_campaign
               SET state = 'PUBLISHED', published_at = ?, updated_at = ?, version = version + 1
             WHERE campaign_id = ?
            """.trimIndent(),
            Timestamp.from(now),
            Timestamp.from(now),
            campaignId,
        )
        return requireNotNull(find(campaignId))
    }

    fun find(campaignId: UUID): LimitedCouponCampaignSnapshot? =
        jdbc
            .query(
                "$BASE_QUERY WHERE campaign.id = ?",
                { row, _ -> row.toSnapshot(eligibleMenus(campaignId)) },
                campaignId,
            ).singleOrNull()

    fun list(
        afterCreatedAt: Instant?,
        afterCampaignId: UUID?,
        limit: Int,
    ): List<LimitedCouponCampaignSnapshot> {
        val paging =
            if (afterCreatedAt == null || afterCampaignId == null) {
                ""
            } else {
                " WHERE (limited.created_at, campaign.id) < (?, ?)"
            }
        val parameters = mutableListOf<Any>()
        if (afterCreatedAt != null && afterCampaignId != null) {
            parameters += Timestamp.from(afterCreatedAt)
            parameters += afterCampaignId
        }
        parameters += limit
        return jdbc.query(
            "$BASE_QUERY$paging ORDER BY limited.created_at DESC, campaign.id DESC LIMIT ?",
            { row, _ ->
                val campaignId = row.getObject("campaign_id", UUID::class.java)
                row.toSnapshot(eligibleMenus(campaignId))
            },
            *parameters.toTypedArray(),
        )
    }

    private fun eligibleMenus(campaignId: UUID): List<UUID> =
        jdbc.query(
            "SELECT menu_id FROM promotion_campaign_eligible_menu WHERE campaign_id = ? ORDER BY menu_id",
            { row, _ -> row.getObject("menu_id", UUID::class.java) },
            campaignId,
        )

    private fun ResultSet.toSnapshot(eligibleMenuIds: List<UUID>) =
        LimitedCouponCampaignSnapshot(
            campaignId = getObject("campaign_id", UUID::class.java),
            storeId = getObject("store_id", UUID::class.java),
            state = LimitedCouponCampaignState.valueOf(getString("limited_state")),
            title = getString("title"),
            summary = getString("summary"),
            bannerAltText = getString("banner_alt_text"),
            banner =
                getString("banner_original_key")?.let {
                    LimitedCouponCampaignBannerPointer(
                        originalKey = it,
                        thumbnailKey = getString("banner_thumbnail_key"),
                        sha256 = getString("banner_sha256"),
                        updatedAt = getTimestamp("banner_updated_at").toInstant(),
                    )
                },
            discount =
                LimitedCouponCampaignDiscount(
                    discountType = CouponDiscountType.valueOf(getString("discount_type")),
                    fixedAmountKrw = getLongOrNull("fixed_amount_krw"),
                    rateBps = getIntOrNull("rate_bps"),
                    maximumDiscountKrw = getLongOrNull("maximum_discount_krw"),
                ),
            minimumOrderKrw = getLong("minimum_eligible_subtotal_krw"),
            allMenusEligible = getBoolean("all_menus_eligible"),
            eligibleMenuIds = eligibleMenuIds,
            cost =
                LimitedCouponCampaignCost(
                    costBearer = CouponCostBearer.valueOf(getString("cost_bearer")),
                    platformShareBps = getInt("platform_share_bps"),
                    storeShareBps = getInt("store_share_bps"),
                ),
            totalQuota = getInt("total_quota"),
            issuedCount = getInt("issued_count"),
            claimStartsAt = getTimestamp("claim_starts_at").toInstant(),
            claimEndsAt = getTimestamp("claim_ends_at").toInstant(),
            couponExpiresAt = getTimestamp("coupon_expires_at").toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            version = getLong("limited_version"),
        )

    private fun ResultSet.getLongOrNull(column: String): Long? = getObject(column)?.let { getLong(column) }

    private fun ResultSet.getIntOrNull(column: String): Int? = getObject(column)?.let { getInt(column) }

    internal companion object {
        const val CREATE_OPERATION = "LIMITED_CAMPAIGN_CREATE_V1"
        const val BANNER_OPERATION = "LIMITED_CAMPAIGN_BANNER_REPLACE_V1"
        const val PUBLISH_OPERATION = "LIMITED_CAMPAIGN_PUBLISH_V1"
        val COMMAND_RETENTION: Duration = Duration.ofDays(90)
        val BASE_QUERY =
            """
            SELECT campaign.id AS campaign_id, campaign.store_id, campaign.discount_type,
                   campaign.fixed_amount_krw, campaign.rate_bps,
                   campaign.minimum_eligible_subtotal_krw, campaign.maximum_discount_krw,
                   campaign.all_menus_eligible, campaign.cost_bearer,
                   campaign.platform_share_bps, campaign.store_share_bps,
                   limited.state AS limited_state, limited.title, limited.summary,
                   limited.banner_alt_text, limited.banner_original_key, limited.banner_thumbnail_key,
                   limited.banner_sha256, limited.banner_updated_at, limited.claim_starts_at, limited.claim_ends_at,
                   limited.coupon_expires_at, limited.created_at, limited.updated_at,
                   limited.version AS limited_version, counter.total_quota, counter.issued_count
              FROM promotion_limited_campaign limited
              JOIN promotion_campaign campaign ON campaign.id = limited.campaign_id
              JOIN promotion_limited_campaign_counter counter ON counter.campaign_id = limited.campaign_id
            """.trimIndent()
    }
}
