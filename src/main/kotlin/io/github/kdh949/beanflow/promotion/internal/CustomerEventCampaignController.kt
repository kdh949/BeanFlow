package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.merchant.api.StoreDisplaySnapshotOperations
import io.github.kdh949.beanflow.merchant.api.StorefrontImageStorageOperations
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.validation.constraints.Size
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
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
    val claimed: Boolean,
)

internal data class CustomerEventCampaignView(
    val campaign: CustomerEventCampaignRecord,
    val storeName: String,
)

internal data class CustomerEventCampaignSort(
    val claimEndsAt: Instant,
    val campaignId: UUID,
)

internal data class CustomerEventCampaignViewPage(
    val campaigns: List<CustomerEventCampaignView>,
    val nextSort: CustomerEventCampaignSort?,
)

@Component
internal class CustomerEventCampaignQueryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listAvailable(
        customerId: UUID,
        now: Instant,
        after: CustomerEventCampaignSort?,
        limit: Int,
    ): List<CustomerEventCampaignRecord> =
        try {
            val boundaryClause =
                if (after == null) "" else "AND (limited.claim_ends_at, campaign.id) > (?, ?)"
            val parameters = mutableListOf<Any>(customerId, Timestamp.from(now), Timestamp.from(now))
            if (after != null) {
                parameters.add(Timestamp.from(after.claimEndsAt))
                parameters.add(after.campaignId)
            }
            parameters.add(limit)
            jdbc.query(
                """
                SELECT campaign.id AS campaign_id, campaign.store_id, limited.title, limited.summary,
                       limited.banner_alt_text, limited.banner_thumbnail_key, campaign.discount_type,
                       campaign.fixed_amount_krw, campaign.rate_bps, campaign.maximum_discount_krw,
                       campaign.minimum_eligible_subtotal_krw,
                       counter.total_quota - counter.issued_count AS remaining_count,
                       limited.claim_ends_at, limited.coupon_expires_at,
                       EXISTS (
                           SELECT 1 FROM promotion_limited_coupon_claim claim
                            WHERE claim.campaign_id = campaign.id AND claim.customer_id = ?
                       ) AS claimed
                  FROM promotion_limited_campaign limited
                  JOIN promotion_campaign campaign ON campaign.id = limited.campaign_id
                  JOIN promotion_limited_campaign_counter counter ON counter.campaign_id = limited.campaign_id
                 WHERE limited.state = 'PUBLISHED'
                   AND campaign.active = true
                   AND limited.banner_thumbnail_key IS NOT NULL
                   AND limited.claim_starts_at <= ?
                   AND limited.claim_ends_at > ?
                   AND counter.issued_count < counter.total_quota
                   $boundaryClause
                 ORDER BY limited.claim_ends_at, campaign.id
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
                        claimed = row.getBoolean("claimed"),
                    )
                },
                *parameters.toTypedArray(),
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
    fun list(
        customerId: UUID,
        now: Instant,
        after: CustomerEventCampaignSort?,
        limit: Int,
    ): CustomerEventCampaignViewPage {
        val records = repository.listAvailable(customerId, now, after, limit + 1)
        val hasMore = records.size > limit
        val campaigns = records.take(limit)
        val boundary = campaigns.lastOrNull().takeIf { hasMore }
        return CustomerEventCampaignViewPage(
            campaigns.map { campaign -> CustomerEventCampaignView(campaign, stores.require(campaign.storeId).name) },
            boundary?.let { CustomerEventCampaignSort(it.claimEndsAt, it.campaignId) },
        )
    }
}

internal data class CustomerEventCampaignPage(
    val campaigns: List<CustomerEventCampaignResponse>,
    val nextCursor: String?,
)

@Service
internal class CustomerEventCampaignService(
    private val transactions: CustomerEventCampaignReadTransaction,
    private val storage: StorefrontImageStorageOperations,
    private val cursors: SignedCursorCodec,
) {
    fun list(
        customerId: UUID,
        now: Instant,
        cursor: String?,
        limit: Int?,
    ): CustomerEventCampaignPage {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) invalid("limit must be between 1 and $MAX_PAGE_SIZE")
        val scope = cursorScope(customerId)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val page = transactions.list(customerId, now, after, pageSize)
        val responses =
            page.campaigns.map { view ->
                val access = storage.access(view.campaign.bannerThumbnailKey)
                CustomerEventCampaignResponse.of(view, access.url, access.expiresAt)
            }
        val nextCursor = page.nextSort?.let { cursors.issue(scope, it, now.plus(CURSOR_TTL)) }
        return CustomerEventCampaignPage(responses, nextCursor)
    }

    private fun cursorScope(customerId: UUID): SignedCursorScope<CustomerEventCampaignSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT|$customerId"),
            sortAdapter =
                object : CursorSortAdapter<CustomerEventCampaignSort> {
                    override fun encode(sort: CustomerEventCampaignSort) = listOf(sort.claimEndsAt.toString(), sort.campaignId.toString())

                    override fun decode(values: List<String>): CustomerEventCampaignSort? {
                        if (values.size != 2) return null
                        return try {
                            CustomerEventCampaignSort(Instant.parse(values[0]), UUID.fromString(values[1]))
                        } catch (_: DateTimeParseException) {
                            null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                },
        )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        const val CURSOR_ENDPOINT = "customer-event-campaigns"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        val CURSOR_TTL: Duration = Duration.ofMinutes(30)
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
    val claimed: Boolean,
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
                claimed = campaign.claimed,
            )
        }
    }
}

internal data class CustomerEventCampaignPageResponse(
    val items: List<CustomerEventCampaignResponse>,
    val page: CustomerEventCampaignPageInfoResponse,
)

internal data class CustomerEventCampaignPageInfoResponse(
    val nextCursor: String?,
)

@RestController
@RequestMapping("/api/v1/me/events")
@Validated
internal class CustomerEventCampaignController(
    private val service: CustomerEventCampaignService,
    private val claims: LimitedCouponClaimService,
    private val clock: Clock,
) {
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): CustomerEventCampaignPageResponse {
        val page = service.list(actor.actorId, clock.instant(), cursor, limit)
        return CustomerEventCampaignPageResponse(page.campaigns, CustomerEventCampaignPageInfoResponse(page.nextCursor))
    }

    @PostMapping("/{campaignId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    fun claim(
        actor: CustomerActor,
        @PathVariable campaignId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): CustomerCouponClaimResponse = claims.claim(actor.actorId, campaignId, idempotencyKey)
}
