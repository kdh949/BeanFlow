package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CustomerEventCampaignRecord(
    val campaignId: UUID,
    val storeId: UUID,
    val title: String,
    val summary: String,
    val bannerAltText: String,
    val bannerThumbnailKey: String,
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val maximumDiscountKrw: Long?,
    val minimumOrderKrw: Long,
    val remainingCount: Int,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
)

internal data class CustomerEventCampaignView(
    val campaign: CustomerEventCampaignRecord,
    val storeName: String,
)

@Component
internal class CustomerEventCampaignQueryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listAvailable(
        now: Instant,
        limit: Int,
    ): List<CustomerEventCampaignRecord> =
        try {
            jdbc.query(
                """
                SELECT campaign.id AS campaign_id, campaign.store_id, limited.title, limited.summary,
                       limited.banner_alt_text, limited.banner_thumbnail_key, campaign.discount_type,
                       campaign.fixed_amount_krw, campaign.rate_bps, campaign.maximum_discount_krw,
                       campaign.minimum_eligible_subtotal_krw,
                       counter.total_quota - counter.issued_count AS remaining_count,
                       limited.claim_ends_at, limited.coupon_expires_at
                  FROM promotion_limited_campaign limited
                  JOIN promotion_campaign campaign ON campaign.id = limited.campaign_id
                  JOIN promotion_limited_campaign_counter counter ON counter.campaign_id = limited.campaign_id
                 WHERE limited.state = 'PUBLISHED'
                   AND campaign.active = true
                   AND limited.banner_thumbnail_key IS NOT NULL
                   AND limited.claim_starts_at <= ?
                   AND limited.claim_ends_at > ?
                   AND counter.issued_count < counter.total_quota
                 ORDER BY limited.published_at DESC, campaign.id DESC
                 LIMIT ?
                """.trimIndent(),
                { row, _ ->
                    CustomerEventCampaignRecord(
                        campaignId = row.getObject("campaign_id", UUID::class.java),
                        storeId = row.getObject("store_id", UUID::class.java),
                        title = row.getString("title"),
                        summary = row.getString("summary"),
                        bannerAltText = row.getString("banner_alt_text"),
                        bannerThumbnailKey = row.getString("banner_thumbnail_key"),
                        discountType = CouponDiscountType.valueOf(row.getString("discount_type")),
                        fixedAmountKrw = row.getObject("fixed_amount_krw")?.let { row.getLong("fixed_amount_krw") },
                        rateBps = row.getObject("rate_bps")?.let { row.getInt("rate_bps") },
                        maximumDiscountKrw = row.getObject("maximum_discount_krw")?.let { row.getLong("maximum_discount_krw") },
                        minimumOrderKrw = row.getLong("minimum_eligible_subtotal_krw"),
                        remainingCount = row.getInt("remaining_count"),
                        claimEndsAt = row.getTimestamp("claim_ends_at").toInstant(),
                        couponExpiresAt = row.getTimestamp("coupon_expires_at").toInstant(),
                    )
                },
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
            )
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Event campaigns are unavailable").also { it.initCause(failure) }
        }
}

@Service
internal class CustomerEventCampaignReadTransaction(
    private val repository: CustomerEventCampaignQueryRepository,
    private val stores: StoreDisplaySnapshotOperations,
) {
    @Transactional(readOnly = true)
    fun list(now: Instant): List<CustomerEventCampaignView> =
        repository.listAvailable(now, MAX_EVENTS).map { campaign ->
            CustomerEventCampaignView(campaign, stores.require(campaign.storeId).name)
        }

    private companion object {
        const val MAX_EVENTS = 50
    }
}

@Service
internal class CustomerEventCampaignService(
    private val transactions: CustomerEventCampaignReadTransaction,
    private val storage: StorefrontImageStorageOperations,
) {
    fun list(now: Instant): List<CustomerEventCampaignResponse> =
        transactions.list(now).map { view ->
            val access = storage.access(view.campaign.bannerThumbnailKey)
            CustomerEventCampaignResponse.of(view, access.url, access.expiresAt)
        }
}

internal data class CustomerEventStoreResponse(
    val storeId: UUID,
    val name: String,
)

internal data class CustomerEventBannerResponse(
    val url: String,
    val expiresAt: Instant,
)

internal data class CustomerEventBenefitResponse(
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val maximumDiscountKrw: Long?,
)

internal data class CustomerEventCampaignResponse(
    val campaignId: UUID,
    val store: CustomerEventStoreResponse,
    val title: String,
    val summary: String,
    val bannerAltText: String,
    val banner: CustomerEventBannerResponse,
    val benefit: CustomerEventBenefitResponse,
    val minimumOrderKrw: Long,
    val remainingCount: Int,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
) {
    companion object {
        fun of(
            view: CustomerEventCampaignView,
            bannerUrl: String,
            bannerAccessExpiresAt: Instant,
        ): CustomerEventCampaignResponse {
            val campaign = view.campaign
            return CustomerEventCampaignResponse(
                campaignId = campaign.campaignId,
                store = CustomerEventStoreResponse(campaign.storeId, view.storeName),
                title = campaign.title,
                summary = campaign.summary,
                bannerAltText = campaign.bannerAltText,
                banner = CustomerEventBannerResponse(bannerUrl, bannerAccessExpiresAt),
                benefit =
                    CustomerEventBenefitResponse(
                        campaign.discountType,
                        campaign.fixedAmountKrw,
                        campaign.rateBps,
                        campaign.maximumDiscountKrw,
                    ),
                minimumOrderKrw = campaign.minimumOrderKrw,
                remainingCount = campaign.remainingCount,
                claimEndsAt = campaign.claimEndsAt,
                couponExpiresAt = campaign.couponExpiresAt,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/me/events")
internal class CustomerEventCampaignController(
    private val service: CustomerEventCampaignService,
    private val clock: Clock,
) {
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(actor: CustomerActor): List<CustomerEventCampaignResponse> {
        actor.actorId
        return service.list(clock.instant())
    }
}
