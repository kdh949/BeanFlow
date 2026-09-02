package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CreateLimitedCouponCampaignDraftCommand
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignCost
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignDiscount
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignSnapshot
import io.github.kdh949.beanflow.promotion.api.LimitedCouponCampaignState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CreateCouponCampaignDraftRequest(
    val storeId: UUID,
    @field:Size(min = 1, max = 80)
    val title: String,
    @field:Size(min = 1, max = 160)
    val summary: String,
    @field:Size(min = 1, max = 200)
    val bannerAltText: String,
    @field:Valid
    val discount: CouponCampaignDiscountRequest,
    @field:Min(0)
    val minimumOrderKrw: Long,
    val allMenusEligible: Boolean,
    val eligibleMenuIds: Set<UUID>,
    @field:Valid
    val cost: CouponCampaignCostRequest,
    @field:Min(1)
    @field:Max(1_000_000)
    val totalQuota: Int,
    val claimStartsAt: Instant,
    val claimEndsAt: Instant,
    val couponExpiresAt: Instant,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class CouponCampaignDiscountRequest(
    val discountType: CouponDiscountType,
    @field:Min(1)
    val fixedAmountKrw: Long? = null,
    @field:Min(1)
    @field:Max(10_000)
    val rateBps: Int? = null,
    @field:Min(1)
    val maximumDiscountKrw: Long? = null,
)

data class CouponCampaignCostRequest(
    val costBearer: CouponCostBearer,
    @field:Min(0)
    @field:Max(10_000)
    val platformShareBps: Int,
    @field:Min(0)
    @field:Max(10_000)
    val storeShareBps: Int,
)

data class CouponCampaignStoreResponse(
    val storeId: UUID,
    val name: String,
)

data class CouponCampaignResponse(
    val campaignId: UUID,
    val store: CouponCampaignStoreResponse,
    val state: LimitedCouponCampaignState,
    val title: String,
    val summary: String,
    val bannerAltText: String,
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
) {
    companion object {
        internal fun of(view: OperatorCouponCampaignView): CouponCampaignResponse {
            val campaign = view.campaign
            return CouponCampaignResponse(
                campaignId = campaign.campaignId,
                store = CouponCampaignStoreResponse(campaign.storeId, view.storeName),
                state = campaign.state,
                title = campaign.title,
                summary = campaign.summary,
                bannerAltText = campaign.bannerAltText,
                discount = campaign.discount,
                minimumOrderKrw = campaign.minimumOrderKrw,
                allMenusEligible = campaign.allMenusEligible,
                eligibleMenuIds = campaign.eligibleMenuIds,
                cost = campaign.cost,
                totalQuota = campaign.totalQuota,
                issuedCount = campaign.issuedCount,
                claimStartsAt = campaign.claimStartsAt,
                claimEndsAt = campaign.claimEndsAt,
                couponExpiresAt = campaign.couponExpiresAt,
                createdAt = campaign.createdAt,
                updatedAt = campaign.updatedAt,
                version = campaign.version,
            )
        }
    }
}

data class CouponCampaignPageResponse(
    val items: List<CouponCampaignResponse>,
    val page: CouponCampaignPageInfo,
)

data class CouponCampaignPageInfo(
    val nextCursor: String?,
)

data class CouponCampaignStoreOptionResponse(
    val storeId: UUID,
    val name: String,
)

data class CouponCampaignMenuOptionResponse(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/coupon-campaigns")
internal class OperatorCouponCampaignController(
    private val service: OperatorCouponCampaignService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun create(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateCouponCampaignDraftRequest,
    ): CouponCampaignResponse =
        CouponCampaignResponse.of(
            service.createDraft(
                OperatorCouponCampaignCommandContext(actorId(actor), idempotencyKey, request.reason),
                request.toCommand(actorId(actor), idempotencyKey),
            ),
        )

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): CouponCampaignPageResponse {
        val page = service.list(actorId(actor), cursor, limit)
        return CouponCampaignPageResponse(page.campaigns.map(CouponCampaignResponse::of), CouponCampaignPageInfo(page.nextCursor))
    }

    @GetMapping("/store-options")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun listStoreOptions(actor: OperatorActor): List<CouponCampaignStoreOptionResponse> =
        service.listStoreOptions(actorId(actor)).map { CouponCampaignStoreOptionResponse(it.storeId, it.name) }

    @GetMapping("/store-options/{storeId}/menus")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun listMenuOptions(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
    ): List<CouponCampaignMenuOptionResponse> =
        service.listMenuOptions(actorId(actor), storeId).map { CouponCampaignMenuOptionResponse(it.menuId, it.name, it.basePriceKrw) }

    @GetMapping("/{campaignId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun find(
        actor: OperatorActor,
        @PathVariable campaignId: UUID,
    ): CouponCampaignResponse = CouponCampaignResponse.of(service.find(actorId(actor), campaignId))

    private fun CreateCouponCampaignDraftRequest.toCommand(
        actorId: UUID,
        idempotencyKey: String,
    ) = CreateLimitedCouponCampaignDraftCommand(
        actorId = actorId,
        idempotencyKey = idempotencyKey,
        storeId = storeId,
        title = title,
        summary = summary,
        bannerAltText = bannerAltText,
        discount =
            LimitedCouponCampaignDiscount(
                discount.discountType,
                discount.fixedAmountKrw,
                discount.rateBps,
                discount.maximumDiscountKrw,
            ),
        minimumOrderKrw = minimumOrderKrw,
        allMenusEligible = allMenusEligible,
        eligibleMenuIds = eligibleMenuIds,
        cost = LimitedCouponCampaignCost(cost.costBearer, cost.platformShareBps, cost.storeShareBps),
        totalQuota = totalQuota,
        claimStartsAt = claimStartsAt,
        claimEndsAt = claimEndsAt,
        couponExpiresAt = couponExpiresAt,
        reason = reason,
        now = Instant.EPOCH,
    )

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
