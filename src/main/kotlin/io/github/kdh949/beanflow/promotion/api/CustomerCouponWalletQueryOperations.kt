package io.github.kdh949.beanflow.promotion.api

import java.time.Instant
import java.util.UUID

/**
 * Customer-scoped, read-only coupon wallet. The order workflow remains the authority that
 * re-validates ownership, state, expiry, store scope and order amount before a coupon is used.
 */
interface CustomerCouponWalletQueryOperations : PromotionApi {
    fun list(command: ListCustomerCouponWalletCommand): CustomerCouponWalletPage
}

data class ListCustomerCouponWalletCommand(
    val customerId: UUID,
    val storeId: String?,
    val cursor: String?,
    val limit: String?,
    val now: Instant,
)

enum class CouponWalletInapplicableReason {
    STORE_NOT_APPLICABLE,
}

data class CustomerCouponWalletBenefit(
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val maximumDiscountKrw: Long?,
)

data class CustomerCouponWalletItem(
    val couponIssuanceId: UUID,
    val benefit: CustomerCouponWalletBenefit,
    val minimumOrderKrw: Long,
    val couponExpiresAt: Instant,
    val applicable: Boolean,
    val reasonCode: CouponWalletInapplicableReason?,
)

data class CustomerCouponWalletPage(
    val items: List<CustomerCouponWalletItem>,
    val nextCursor: String?,
)
