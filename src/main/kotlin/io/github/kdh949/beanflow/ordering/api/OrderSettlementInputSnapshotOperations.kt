package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class SettlementCouponCostBearer {
    PLATFORM,
    STORE,
    SHARED,
}

data class OrderSettlementInputSnapshot(
    val orderId: UUID,
    val storeId: UUID,
    val storeSettlementTermsVersionId: UUID,
    val storeSettlementTermsSourceReference: String,
    val couponReservationId: UUID?,
    val couponCampaignId: UUID?,
    val couponCampaignVersion: Long?,
    val couponCostBearer: SettlementCouponCostBearer?,
    val couponPlatformShareBps: Int?,
    val couponStoreShareBps: Int?,
    val couponDiscountKrw: Long,
    val platformCouponCostKrw: Long,
    val couponCostKrw: Long,
    val pointReservationId: UUID?,
    val pointAllocationHash: String?,
    val pointsAppliedKrw: Long,
    val pointCostKrw: Long,
    val grossPaidKrw: Long,
    val feeBaseKrw: Long,
    val feeRateBps: Int,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
    val currency: String,
    val snapshotSchemaVersion: Int,
    val canonicalSnapshotHash: String,
    val createdAt: Instant,
)

interface OrderSettlementInputSnapshotOperations {
    fun read(orderId: UUID): OrderSettlementInputSnapshot
}
