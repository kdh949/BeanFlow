package io.github.kdh949.beanflow.promotion.api

import java.time.Instant
import java.util.UUID

data class CouponQuoteCommand(
    val customerId: UUID,
    val storeId: UUID,
    val couponIssuanceId: UUID,
    val lines: List<CouponPricingLine>,
)

data class CouponQuoteSnapshot(
    val couponIssuanceId: UUID,
    val issuanceVersion: Long,
    val issuanceState: String,
    val couponExpiresAt: Instant,
    val originalIssuanceId: UUID?,
    val discountKrw: Long,
    val eligibleLineSequences: Set<Int>,
    val allMenusEligible: Boolean,
    val eligibleMenuIds: Set<UUID>,
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val minimumEligibleSubtotalKrw: Long,
    val maximumDiscountKrw: Long?,
    val campaignId: UUID,
    val campaignVersion: Long,
    val costBearer: CouponCostBearer,
    val platformShareBps: Int,
    val storeShareBps: Int,
    val platformCouponCostKrw: Long,
    val storeCouponCostKrw: Long,
)

interface CouponQuoteOperations {
    fun inspect(command: CouponQuoteCommand): CouponQuoteSnapshot

    fun lockForOrderCreation(command: CouponQuoteCommand): CouponQuoteSnapshot
}
