package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.SettlementCouponCostBearer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "ordering_order_settlement_input_snapshot")
internal class OrderSettlementInputSnapshotEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "store_settlement_terms_version_id", nullable = false)
    val storeSettlementTermsVersionId: UUID,
    @Column(name = "store_settlement_terms_source_reference", nullable = false, length = 240)
    val storeSettlementTermsSourceReference: String,
    @Column(name = "coupon_reservation_id")
    val couponReservationId: UUID?,
    @Column(name = "coupon_campaign_id")
    val couponCampaignId: UUID?,
    @Column(name = "coupon_campaign_version")
    val couponCampaignVersion: Long?,
    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_cost_bearer")
    val couponCostBearer: SettlementCouponCostBearer?,
    @Column(name = "coupon_platform_share_bps")
    val couponPlatformShareBps: Int?,
    @Column(name = "coupon_store_share_bps")
    val couponStoreShareBps: Int?,
    @Column(name = "coupon_discount_krw", nullable = false)
    val couponDiscountKrw: Long,
    @Column(name = "platform_coupon_cost_krw", nullable = false)
    val platformCouponCostKrw: Long,
    @Column(name = "coupon_cost_krw", nullable = false)
    val couponCostKrw: Long,
    @Column(name = "point_reservation_id")
    val pointReservationId: UUID?,
    @Column(name = "point_allocation_hash", length = 64)
    val pointAllocationHash: String?,
    @Column(name = "points_applied_krw", nullable = false)
    val pointsAppliedKrw: Long,
    @Column(name = "point_cost_krw", nullable = false)
    val pointCostKrw: Long,
    @Column(name = "gross_paid_krw", nullable = false)
    val grossPaidKrw: Long,
    @Column(name = "fee_base_krw", nullable = false)
    val feeBaseKrw: Long,
    @Column(name = "fee_rate_bps", nullable = false)
    val feeRateBps: Int,
    @Column(name = "fee_krw", nullable = false)
    val feeKrw: Long,
    @Column(name = "benefit_cost_krw", nullable = false)
    val benefitCostKrw: Long,
    @Column(name = "net_settlement_krw", nullable = false)
    val netSettlementKrw: Long,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Column(name = "snapshot_schema_version", nullable = false)
    val snapshotSchemaVersion: Int,
    @Column(name = "canonical_snapshot_hash", nullable = false, length = 64)
    val canonicalSnapshotHash: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal interface OrderSettlementInputSnapshotJpaRepository : JpaRepository<OrderSettlementInputSnapshotEntity, UUID>
