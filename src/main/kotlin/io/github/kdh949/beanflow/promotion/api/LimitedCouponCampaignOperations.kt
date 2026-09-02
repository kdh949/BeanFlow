package io.github.kdh949.beanflow.promotion.api

import io.github.kdh949.beanflow.merchant.api.PreparedStorefrontImage
import java.time.Instant
import java.util.UUID

enum class LimitedCouponCampaignState {
    DRAFT,
    PUBLISHED,
    STOPPED,
}

data class LimitedCouponCampaignDiscount(
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long? = null,
    val rateBps: Int? = null,
    val maximumDiscountKrw: Long? = null,
)

data class LimitedCouponCampaignCost(
    val costBearer: CouponCostBearer,
    val platformShareBps: Int,
    val storeShareBps: Int,
)

data class CreateLimitedCouponCampaignDraftCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val title: String,
    val summary: String,
    val bannerAltText: String,
    val discount: LimitedCouponCampaignDiscount,
    val minimumOrderKrw: Long,
    val allMenusEligible: Boolean,
    val eligibleMenuIds: Set<UUID>,
    val cost: LimitedCouponCampaignCost,
    val totalQuota: Int,
    val claimStartsAt: Instant,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
    val reason: String,
    val now: Instant,
)

data class LimitedCouponCampaignSnapshot(
    val campaignId: UUID,
    val storeId: UUID,
    val state: LimitedCouponCampaignState,
    val title: String,
    val summary: String,
    val bannerAltText: String,
    val banner: LimitedCouponCampaignBannerPointer?,
    val discount: LimitedCouponCampaignDiscount,
    val minimumOrderKrw: Long,
    val allMenusEligible: Boolean,
    val eligibleMenuIds: List<UUID>,
    val cost: LimitedCouponCampaignCost,
    val totalQuota: Int,
    val issuedCount: Int,
    val claimStartsAt: Instant,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class LimitedCouponCampaignBannerPointer(
    val originalKey: String,
    val thumbnailKey: String,
    val sha256: String,
    val updatedAt: Instant,
)

data class ReplaceLimitedCouponCampaignBannerCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedVersion: Long,
    val prepared: PreparedStorefrontImage,
    val reason: String,
    val now: Instant,
)

data class ReplayLimitedCouponCampaignBannerCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedVersion: Long,
    val sha256: String,
    val reason: String,
)

data class PublishLimitedCouponCampaignCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedVersion: Long,
    val reason: String,
    val now: Instant,
)

data class StopLimitedCouponCampaignCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedVersion: Long,
    val reason: String,
    val now: Instant,
)

data class LimitedCouponCampaignPage(
    val campaigns: List<LimitedCouponCampaignSnapshot>,
    val nextCreatedAt: Instant?,
    val nextCampaignId: UUID?,
)

interface LimitedCouponCampaignOperations {
    fun createDraft(command: CreateLimitedCouponCampaignDraftCommand): LimitedCouponCampaignSnapshot

    fun find(campaignId: UUID): LimitedCouponCampaignSnapshot?

    fun list(
        afterCreatedAt: Instant?,
        afterCampaignId: UUID?,
        limit: Int,
    ): LimitedCouponCampaignPage

    fun replayBanner(command: ReplayLimitedCouponCampaignBannerCommand): LimitedCouponCampaignSnapshot?

    fun replaceBanner(command: ReplaceLimitedCouponCampaignBannerCommand): LimitedCouponCampaignSnapshot

    fun publish(command: PublishLimitedCouponCampaignCommand): LimitedCouponCampaignSnapshot

    fun stop(command: StopLimitedCouponCampaignCommand): LimitedCouponCampaignSnapshot
}
