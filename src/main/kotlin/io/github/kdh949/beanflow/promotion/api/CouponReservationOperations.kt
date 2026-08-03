package io.github.kdh949.beanflow.promotion.api

import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import java.time.Instant
import java.util.UUID

data class CouponPricingLine(
    val lineSequence: Int,
    val menuId: UUID,
    val grossKrw: Long,
)

data class ReserveCouponCommand(
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val couponIssuanceId: UUID,
    val lines: List<CouponPricingLine>,
    val reservationExpiresAt: Instant,
    val sourceReference: String,
)

enum class CouponDiscountType {
    FIXED_KRW,
    RATE_BPS,
}

enum class CouponCostBearer {
    PLATFORM,
    STORE,
    SHARED,
}

data class CouponReservationQuote(
    val reservationId: UUID,
    val discountKrw: Long,
    val eligibleLineSequences: Set<Int>,
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

enum class ExpiredCouponRestorationMode {
    COMPENSATE_WITH_NEW_ISSUANCE,
    PRESERVE_ORIGINAL_EXPIRY,
}

data class RestoreCouponAfterTerminationCommand(
    val orderId: UUID,
    val terminatedAt: Instant,
    val sourceReference: String,
    val trigger: OrderTerminationTrigger,
    val policyVersionId: Long,
    val mode: ExpiredCouponRestorationMode,
    val compensationValidityDays: Int,
)

interface CouponReservationOperations {
    fun reserve(command: ReserveCouponCommand): CouponReservationQuote

    fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport

    fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun restoreUsedAfterTermination(command: RestoreCouponAfterTerminationCommand): ReservationTransitionReport
}
