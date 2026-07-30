package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.CouponReservationQuote
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.promotion.api.RestoreCouponByRejectionCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class CouponReservationService(
    private val campaignRepository: CampaignJpaRepository,
    private val eligibleMenuRepository: CampaignEligibleMenuJpaRepository,
    private val issuanceRepository: CouponIssuanceJpaRepository,
    private val reservationRepository: CouponReservationJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
) : CouponReservationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun reserve(command: ReserveCouponCommand): CouponReservationQuote {
        if (command.sourceReference.isBlank() || command.lines.isEmpty()) {
            fail(FailureCode.INVALID_REQUEST, "Coupon source and pricing lines are required")
        }
        val issuance =
            issuanceRepository.findLockedById(command.couponIssuanceId)
                ?: fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon issuance is not available")
        reservationRepository.findBySourceReference(command.sourceReference)?.let {
            if (it.orderId == command.orderId && it.couponIssuanceId == command.couponIssuanceId) {
                return it.toQuote()
            }
            fail(FailureCode.ORDER_STATE_CONFLICT, "Coupon source reference was reused")
        }
        val now = clock.instant()
        if (issuance.customerId != command.customerId ||
            issuance.state !in setOf(CouponIssuanceState.AVAILABLE, CouponIssuanceState.RESTORED) ||
            !now.isBefore(issuance.couponExpiresAt)
        ) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon issuance is expired, used, or owned by another customer")
        }
        val campaign =
            campaignRepository.findById(issuance.campaignId).orElse(null)
                ?: fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign is missing")
        if (!campaign.active || campaign.storeId != command.storeId) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign is not available for the store")
        }
        val targetMenus =
            if (campaign.allMenusEligible) {
                null
            } else {
                eligibleMenuRepository
                    .findAllByCampaignId(campaign.id)
                    .map { it.menuId }
                    .toSet()
                    .ifEmpty { fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign has no eligible menu") }
            }
        val eligibleLines = command.lines.filter { targetMenus == null || it.menuId in targetMenus }
        val eligibleSubtotal =
            eligibleLines.fold(0L) { total, line ->
                if (line.grossKrw < 0) fail(FailureCode.INVALID_REQUEST, "Line gross must not be negative")
                try {
                    Math.addExact(total, line.grossKrw)
                } catch (_: ArithmeticException) {
                    fail(FailureCode.INVALID_REQUEST, "Eligible subtotal exceeds supported range")
                }
            }
        if (eligibleSubtotal < campaign.minimumEligibleSubtotalKrw) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon minimum eligible subtotal is not met")
        }
        val discount = calculateDiscount(campaign, eligibleSubtotal)
        if (discount <= 0) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon discount is zero")
        }

        val reservation =
            CouponReservationEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                couponIssuanceId = issuance.id,
                state = CouponReservationState.RESERVED,
                discountKrw = discount,
                eligibleLineSequences = eligibleLines.map { it.lineSequence }.sorted().joinToString(","),
                discountType = campaign.discountType,
                fixedAmountKrw = campaign.fixedAmountKrw,
                rateBps = campaign.rateBps,
                minimumEligibleSubtotalKrw = campaign.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = campaign.maximumDiscountKrw,
                reservationExpiresAt = command.reservationExpiresAt,
                sourceReference = command.sourceReference,
                createdAt = now,
                updatedAt = now,
            )
        issuance.state = CouponIssuanceState.RESERVED
        issuance.reservedOrderId = command.orderId
        reservationRepository.save(reservation)
        return reservation.toQuote()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, null, confirm = true)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport = transition(orderId, sourceReference, now, confirm = false)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun restoreUsedByRejection(command: RestoreCouponByRejectionCommand): ReservationTransitionReport {
        if (command.sourceReference.isBlank() || command.compensationValidityDays !in 1..365) {
            fail(FailureCode.INVALID_REQUEST, "Coupon restoration source and validity are invalid")
        }
        val current =
            reservationRepository.findByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val issuance =
            issuanceRepository.findLockedById(current.couponIssuanceId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Used coupon issuance is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.state == CouponReservationState.RESTORED) {
            return if (reservation.restorationSourceReference == command.sourceReference) {
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
            } else {
                report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
            }
        }
        if (reservation.state != CouponReservationState.USED ||
            issuance.state != CouponIssuanceState.USED
        ) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }
        if (command.rejectedAt.isBefore(issuance.couponExpiresAt)) {
            issuance.state = CouponIssuanceState.RESTORED
            issuance.reservedOrderId = null
        } else if (command.mode == ExpiredCouponRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE) {
            issuanceRepository.save(
                CouponIssuanceEntity(
                    id = identifierSource.next(),
                    campaignId = issuance.campaignId,
                    customerId = issuance.customerId,
                    state = CouponIssuanceState.RESTORED,
                    couponExpiresAt =
                        command.rejectedAt.plusSeconds(
                            command.compensationValidityDays.toLong() * 86_400,
                        ),
                    originalIssuanceId = issuance.id,
                    restorationSourceReference = command.sourceReference,
                ),
            )
        }
        reservation.state = CouponReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.updatedAt = command.rejectedAt
        return report(ReservationTransitionResult.APPLIED, reservation.id)
    }

    private fun transition(
        orderId: UUID,
        sourceReference: String,
        now: Instant?,
        confirm: Boolean,
    ): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val issuance =
            issuanceRepository.findLockedById(current.couponIssuanceId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved coupon issuance is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.sourceReference != sourceReference) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Coupon transition source does not match")
        }
        val result =
            when {
                confirm && reservation.state == CouponReservationState.RESERVED -> {
                    issuance.state = CouponIssuanceState.USED
                    reservation.state = CouponReservationState.USED
                    reservation.updatedAt = clock.instant()
                    ReservationTransitionResult.APPLIED
                }

                !confirm && reservation.state == CouponReservationState.RESERVED -> {
                    issuance.state = CouponIssuanceState.AVAILABLE
                    issuance.reservedOrderId = null
                    reservation.state = CouponReservationState.RELEASED
                    reservation.updatedAt = now!!
                    ReservationTransitionResult.APPLIED
                }

                confirm && reservation.state == CouponReservationState.USED -> {
                    ReservationTransitionResult.ALREADY_APPLIED
                }

                !confirm && reservation.state == CouponReservationState.RELEASED -> {
                    ReservationTransitionResult.ALREADY_APPLIED
                }

                else -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }
            }
        return report(result, reservation.id)
    }

    private fun report(
        result: ReservationTransitionResult,
        vararg ids: UUID,
    ) = ReservationTransitionReport(result, ids.toList())

    private fun calculateDiscount(
        campaign: CampaignEntity,
        eligibleSubtotal: Long,
    ): Long =
        when (campaign.discountType) {
            CouponDiscountType.FIXED_KRW -> {
                minOf(campaign.fixedAmountKrw ?: invalidCampaign(), eligibleSubtotal)
            }

            CouponDiscountType.RATE_BPS -> {
                val rate = campaign.rateBps ?: invalidCampaign()
                val raw =
                    BigInteger
                        .valueOf(eligibleSubtotal)
                        .multiply(BigInteger.valueOf(rate.toLong()))
                        .divide(BigInteger.valueOf(10_000))
                        .longValueExact()
                campaign.maximumDiscountKrw?.let { minOf(raw, it) } ?: raw
            }
        }

    private fun CouponReservationEntity.toQuote(): CouponReservationQuote =
        CouponReservationQuote(
            reservationId = id,
            discountKrw = discountKrw,
            eligibleLineSequences =
                eligibleLineSequences
                    .takeIf(String::isNotBlank)
                    ?.split(",")
                    ?.map(String::toInt)
                    ?.toSet()
                    .orEmpty(),
            discountType = discountType,
            fixedAmountKrw = fixedAmountKrw,
            rateBps = rateBps,
            minimumEligibleSubtotalKrw = minimumEligibleSubtotalKrw,
            maximumDiscountKrw = maximumDiscountKrw,
        )

    private fun invalidCampaign(): Nothing = fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign discount fields are invalid")

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}
