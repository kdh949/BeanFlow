package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "promotion_campaign")
internal class CampaignEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(nullable = false)
    val active: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    val discountType: CouponDiscountType,
    @Column(name = "fixed_amount_krw")
    val fixedAmountKrw: Long?,
    @Column(name = "rate_bps")
    val rateBps: Int?,
    @Column(name = "minimum_eligible_subtotal_krw", nullable = false)
    val minimumEligibleSubtotalKrw: Long,
    @Column(name = "maximum_discount_krw")
    val maximumDiscountKrw: Long?,
    @Column(name = "all_menus_eligible", nullable = false)
    val allMenusEligible: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_bearer")
    val costBearer: CouponCostBearer?,
    @Column(name = "platform_share_bps")
    val platformShareBps: Int?,
    @Column(name = "store_share_bps")
    val storeShareBps: Int?,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "promotion_campaign_eligible_menu")
internal class CampaignEligibleMenuEntity(
    @Id
    val id: UUID,
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
)

internal enum class CouponIssuanceState {
    AVAILABLE,
    RESERVED,
    USED,
    RESTORED,
}

@Entity
@Table(name = "promotion_coupon_issuance")
internal class CouponIssuanceEntity(
    @Id
    val id: UUID,
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,
    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: CouponIssuanceState,
    @Column(name = "coupon_expires_at", nullable = false)
    val couponExpiresAt: Instant,
    @Column(name = "reserved_order_id")
    var reservedOrderId: UUID? = null,
    @Column(name = "original_issuance_id")
    val originalIssuanceId: UUID? = null,
    @Column(name = "restoration_source_reference", length = 240)
    val restorationSourceReference: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "restoration_trigger")
    val restorationTrigger: OrderTerminationTrigger? = null,
    @Column(name = "restoration_policy_version_id")
    val restorationPolicyVersionId: Long? = null,
    @Version
    var version: Long = 0,
)

internal enum class CouponReservationState {
    RESERVED,
    USED,
    RELEASED,
    RESTORED,
}

internal enum class CouponRestorationDisposition {
    ORIGINAL_RESTORED,
    COMPENSATION_ISSUED,
    SKIPPED_EXPIRED,
}

@Entity
@Table(name = "promotion_coupon_reservation")
internal class CouponReservationEntity(
    @Id
    val id: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "coupon_issuance_id", nullable = false)
    val couponIssuanceId: UUID,
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,
    @Column(name = "campaign_version", nullable = false)
    val campaignVersion: Long,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: CouponReservationState,
    @Column(name = "discount_krw", nullable = false)
    val discountKrw: Long,
    @Column(name = "eligible_line_sequences", nullable = false)
    val eligibleLineSequences: String,
    @Column(name = "all_menus_eligible", nullable = false)
    val allMenusEligible: Boolean,
    @Column(name = "eligible_menu_ids", nullable = false, length = 4000)
    val eligibleMenuIds: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    val discountType: CouponDiscountType,
    @Column(name = "fixed_amount_krw")
    val fixedAmountKrw: Long?,
    @Column(name = "rate_bps")
    val rateBps: Int?,
    @Column(name = "minimum_eligible_subtotal_krw", nullable = false)
    val minimumEligibleSubtotalKrw: Long,
    @Column(name = "maximum_discount_krw")
    val maximumDiscountKrw: Long?,
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_bearer", nullable = false)
    val costBearer: CouponCostBearer,
    @Column(name = "platform_share_bps", nullable = false)
    val platformShareBps: Int,
    @Column(name = "store_share_bps", nullable = false)
    val storeShareBps: Int,
    @Column(name = "platform_coupon_cost_krw", nullable = false)
    val platformCouponCostKrw: Long,
    @Column(name = "store_coupon_cost_krw", nullable = false)
    val storeCouponCostKrw: Long,
    @Column(name = "reservation_expires_at", nullable = false)
    val reservationExpiresAt: Instant,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "restoration_source_reference", length = 240)
    var restorationSourceReference: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "restoration_trigger")
    var restorationTrigger: OrderTerminationTrigger? = null,
    @Column(name = "restoration_policy_version_id")
    var restorationPolicyVersionId: Long? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "restoration_disposition")
    var restorationDisposition: CouponRestorationDisposition? = null,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "promotion_compensation_coupon_terms_snapshot")
internal class CompensationCouponTermsSnapshotEntity(
    @Id
    @Column(name = "coupon_issuance_id")
    val couponIssuanceId: UUID,
    @Column(name = "campaign_id", nullable = false)
    val campaignId: UUID,
    @Column(name = "campaign_version", nullable = false)
    val campaignVersion: Long,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    val discountType: CouponDiscountType,
    @Column(name = "fixed_amount_krw")
    val fixedAmountKrw: Long?,
    @Column(name = "rate_bps")
    val rateBps: Int?,
    @Column(name = "minimum_eligible_subtotal_krw", nullable = false)
    val minimumEligibleSubtotalKrw: Long,
    @Column(name = "maximum_discount_krw")
    val maximumDiscountKrw: Long?,
    @Column(name = "all_menus_eligible", nullable = false)
    val allMenusEligible: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_bearer", nullable = false)
    val costBearer: CouponCostBearer,
    @Column(name = "platform_share_bps", nullable = false)
    val platformShareBps: Int,
    @Column(name = "store_share_bps", nullable = false)
    val storeShareBps: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

@Entity
@Table(name = "promotion_compensation_coupon_eligible_menu")
internal class CompensationCouponEligibleMenuEntity(
    @Id
    val id: UUID,
    @Column(name = "coupon_issuance_id", nullable = false)
    val couponIssuanceId: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
)

internal interface CampaignJpaRepository : JpaRepository<CampaignEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from CampaignEntity campaign where campaign.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): CampaignEntity?
}

internal interface CampaignEligibleMenuJpaRepository : JpaRepository<CampaignEligibleMenuEntity, UUID> {
    fun findAllByCampaignId(campaignId: UUID): List<CampaignEligibleMenuEntity>
}

internal interface CouponIssuanceJpaRepository : JpaRepository<CouponIssuanceEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select issuance from CouponIssuanceEntity issuance where issuance.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): CouponIssuanceEntity?
}

internal interface CouponReservationJpaRepository : JpaRepository<CouponReservationEntity, UUID> {
    fun findBySourceReference(sourceReference: String): CouponReservationEntity?

    fun findByOrderId(orderId: UUID): CouponReservationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from CouponReservationEntity reservation where reservation.orderId = :orderId")
    fun findLockedByOrderId(
        @Param("orderId") orderId: UUID,
    ): CouponReservationEntity?
}

internal interface CompensationCouponTermsSnapshotJpaRepository : JpaRepository<CompensationCouponTermsSnapshotEntity, UUID>

internal interface CompensationCouponEligibleMenuJpaRepository : JpaRepository<CompensationCouponEligibleMenuEntity, UUID> {
    fun findAllByCouponIssuanceId(couponIssuanceId: UUID): List<CompensationCouponEligibleMenuEntity>
}
