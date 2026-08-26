package io.github.kdh949.beanflow.promotion.internal

import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponPricingLine
import io.github.kdh949.beanflow.promotion.api.CouponQuoteCommand
import io.github.kdh949.beanflow.promotion.api.CouponQuoteOperations
import io.github.kdh949.beanflow.promotion.api.CouponQuoteSnapshot
import io.github.kdh949.beanflow.promotion.api.CouponReservationOperations
import io.github.kdh949.beanflow.promotion.api.CouponReservationQuote
import io.github.kdh949.beanflow.promotion.api.ExpiredCouponRestorationMode
import io.github.kdh949.beanflow.promotion.api.ReserveCouponCommand
import io.github.kdh949.beanflow.promotion.api.RestoreCouponAfterTerminationCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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
    private val compensationTermsRepository: CompensationCouponTermsSnapshotJpaRepository,
    private val compensationEligibleMenuRepository: CompensationCouponEligibleMenuJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : CouponReservationOperations,
    CouponQuoteOperations {
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun inspect(command: CouponQuoteCommand): CouponQuoteSnapshot = quote(command, locked = false)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockForOrderCreation(command: CouponQuoteCommand): CouponQuoteSnapshot = quote(command, locked = true)

    private fun quote(
        command: CouponQuoteCommand,
        locked: Boolean,
    ): CouponQuoteSnapshot {
        if (command.lines.isEmpty()) {
            fail(FailureCode.INVALID_REQUEST, "Coupon pricing lines are required")
        }
        val issuance =
            if (locked) {
                issuanceRepository.findLockedById(command.couponIssuanceId)
            } else {
                issuanceRepository.findById(command.couponIssuanceId).orElse(null)
            } ?: fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon issuance is not available")
        val now = clock.instant()
        if (issuance.customerId != command.customerId ||
            issuance.state !in setOf(CouponIssuanceState.AVAILABLE, CouponIssuanceState.RESTORED) ||
            !now.isBefore(issuance.couponExpiresAt)
        ) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon issuance is expired, used, or owned by another customer")
        }
        val terms = termsFor(issuance, command.storeId, locked)
        val targetMenus =
            if (terms.allMenusEligible) {
                null
            } else {
                terms.eligibleMenuIds.ifEmpty {
                    fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon terms have no eligible menu")
                }
            }
        val eligibleLines = command.lines.filter { targetMenus == null || it.menuId in targetMenus }
        val eligibleSubtotal = eligibleSubtotal(eligibleLines)
        if (eligibleSubtotal < terms.minimumEligibleSubtotalKrw) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon minimum eligible subtotal is not met")
        }
        val discount = calculateDiscount(terms, eligibleSubtotal)
        if (discount <= 0) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon discount is zero")
        }
        val burden = calculateBurden(terms, discount)
        return CouponQuoteSnapshot(
            couponIssuanceId = issuance.id,
            issuanceVersion = issuance.version,
            issuanceState = issuance.state.name,
            couponExpiresAt = issuance.couponExpiresAt,
            originalIssuanceId = issuance.originalIssuanceId,
            discountKrw = discount,
            eligibleLineSequences = eligibleLines.map(CouponPricingLine::lineSequence).toSortedSet(),
            allMenusEligible = terms.allMenusEligible,
            eligibleMenuIds = terms.eligibleMenuIds,
            discountType = terms.discountType,
            fixedAmountKrw = terms.fixedAmountKrw,
            rateBps = terms.rateBps,
            minimumEligibleSubtotalKrw = terms.minimumEligibleSubtotalKrw,
            maximumDiscountKrw = terms.maximumDiscountKrw,
            campaignId = terms.campaignId,
            campaignVersion = terms.campaignVersion,
            costBearer = burden.costBearer,
            platformShareBps = burden.platformShareBps,
            storeShareBps = burden.storeShareBps,
            platformCouponCostKrw = burden.platformCouponCostKrw,
            storeCouponCostKrw = burden.storeCouponCostKrw,
        )
    }

    private fun eligibleSubtotal(lines: List<io.github.kdh949.beanflow.promotion.api.CouponPricingLine>): Long =
        lines.fold(0L) { total, line ->
            if (line.grossKrw < 0) fail(FailureCode.INVALID_REQUEST, "Line gross must not be negative")
            try {
                Math.addExact(total, line.grossKrw)
            } catch (_: ArithmeticException) {
                fail(FailureCode.INVALID_REQUEST, "Eligible subtotal exceeds supported range")
            }
        }

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
        val terms = termsFor(issuance, command.storeId)
        val targetMenus =
            if (terms.allMenusEligible) {
                null
            } else {
                terms.eligibleMenuIds.ifEmpty { fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon terms have no eligible menu") }
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
        if (eligibleSubtotal < terms.minimumEligibleSubtotalKrw) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon minimum eligible subtotal is not met")
        }
        val discount = calculateDiscount(terms, eligibleSubtotal)
        if (discount <= 0) {
            fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon discount is zero")
        }
        val burden = calculateBurden(terms, discount)

        val reservation =
            CouponReservationEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                couponIssuanceId = issuance.id,
                campaignId = terms.campaignId,
                campaignVersion = terms.campaignVersion,
                storeId = terms.storeId,
                state = CouponReservationState.RESERVED,
                discountKrw = discount,
                eligibleLineSequences = eligibleLines.map { it.lineSequence }.sorted().joinToString(","),
                allMenusEligible = terms.allMenusEligible,
                eligibleMenuIds = terms.eligibleMenuIds.sorted().joinToString(","),
                discountType = terms.discountType,
                fixedAmountKrw = terms.fixedAmountKrw,
                rateBps = terms.rateBps,
                minimumEligibleSubtotalKrw = terms.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = terms.maximumDiscountKrw,
                costBearer = burden.costBearer,
                platformShareBps = burden.platformShareBps,
                storeShareBps = burden.storeShareBps,
                platformCouponCostKrw = burden.platformCouponCostKrw,
                storeCouponCostKrw = burden.storeCouponCostKrw,
                reservationExpiresAt = command.reservationExpiresAt,
                sourceReference = command.sourceReference,
                createdAt = now,
                updatedAt = now,
            )
        issuance.state = CouponIssuanceState.RESERVED
        issuance.reservedOrderId = command.orderId
        reservationRepository.save(reservation)
        issuance.restorationTrigger?.let { trigger ->
            afterCommit {
                meterRegistry
                    .counter(
                        "beanflow.coupon.compensation.redemption.count",
                        "trigger",
                        trigger.lowercase(),
                        "outcome",
                        "succeeded",
                    ).increment()
            }
        }
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
    override fun restoreUsedAfterTermination(command: RestoreCouponAfterTerminationCommand): ReservationTransitionReport {
        if (command.sourceReference.isBlank() || command.policyVersionId < 1 || command.compensationValidityDays !in 1..365) {
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
            return if (reservation.restorationSourceReference == command.sourceReference &&
                reservation.restorationTrigger == command.trigger.name &&
                reservation.restorationPolicyVersionId == command.policyVersionId
            ) {
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
            } else {
                sourceConflict(command, "Coupon restoration metadata conflicts")
            }
        }
        if (reservation.state != CouponReservationState.USED ||
            issuance.state != CouponIssuanceState.USED
        ) {
            sourceConflict(command, "Coupon reservation is not used for termination restoration")
        }
        val disposition =
            if (command.terminatedAt.isBefore(issuance.couponExpiresAt)) {
                issuance.state = CouponIssuanceState.RESTORED
                issuance.reservedOrderId = null
                CouponRestorationDisposition.ORIGINAL_RESTORED
            } else if (command.mode == ExpiredCouponRestorationMode.COMPENSATE_WITH_NEW_ISSUANCE) {
                createCompensationIssuance(issuance, reservation, command)
                CouponRestorationDisposition.COMPENSATION_ISSUED
            } else {
                CouponRestorationDisposition.SKIPPED_EXPIRED
            }
        reservation.state = CouponReservationState.RESTORED
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = command.trigger.name
        reservation.restorationPolicyVersionId = command.policyVersionId
        reservation.restorationDisposition = disposition
        reservation.updatedAt = command.terminatedAt
        recordRestorationMetric(
            command,
            disposition,
            if (disposition == CouponRestorationDisposition.SKIPPED_EXPIRED) 0 else reservation.discountKrw,
        )
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

    private fun termsFor(
        issuance: CouponIssuanceEntity,
        storeId: UUID,
        locked: Boolean = true,
    ): CouponTerms =
        if (issuance.originalIssuanceId == null) {
            val campaign =
                if (locked) {
                    campaignRepository.findLockedById(issuance.campaignId)
                } else {
                    campaignRepository.findById(issuance.campaignId).orElse(null)
                }
                    ?: fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign is missing")
            if (!campaign.active || campaign.storeId != storeId) {
                fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign is not available for the store")
            }
            CouponTerms(
                campaignId = campaign.id,
                campaignVersion = campaign.version,
                storeId = campaign.storeId,
                discountType = campaign.discountType,
                fixedAmountKrw = campaign.fixedAmountKrw,
                rateBps = campaign.rateBps,
                minimumEligibleSubtotalKrw = campaign.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = campaign.maximumDiscountKrw,
                allMenusEligible = campaign.allMenusEligible,
                eligibleMenuIds =
                    if (campaign.allMenusEligible) {
                        emptySet()
                    } else {
                        eligibleMenuRepository.findAllByCampaignId(campaign.id).map { it.menuId }.toSet()
                    },
                costBearer = campaign.costBearer,
                platformShareBps = campaign.platformShareBps,
                storeShareBps = campaign.storeShareBps,
            )
        } else {
            val snapshot =
                compensationTermsRepository
                    .findById(issuance.id)
                    .orElseThrow {
                        DomainFailure(
                            FailureCode.SETTLEMENT_INPUT_UNAVAILABLE,
                            "Compensation coupon terms snapshot is missing",
                        )
                    }
            if (snapshot.storeId != storeId) {
                fail(FailureCode.COUPON_NOT_AVAILABLE, "Compensation coupon belongs to another store")
            }
            CouponTerms(
                campaignId = snapshot.campaignId,
                campaignVersion = snapshot.campaignVersion,
                storeId = snapshot.storeId,
                discountType = snapshot.discountType,
                fixedAmountKrw = snapshot.fixedAmountKrw,
                rateBps = snapshot.rateBps,
                minimumEligibleSubtotalKrw = snapshot.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = snapshot.maximumDiscountKrw,
                allMenusEligible = snapshot.allMenusEligible,
                eligibleMenuIds =
                    compensationEligibleMenuRepository
                        .findAllByCouponIssuanceId(issuance.id)
                        .map { it.menuId }
                        .toSet(),
                costBearer = snapshot.costBearer,
                platformShareBps = snapshot.platformShareBps,
                storeShareBps = snapshot.storeShareBps,
            )
        }

    private fun createCompensationIssuance(
        original: CouponIssuanceEntity,
        reservation: CouponReservationEntity,
        command: RestoreCouponAfterTerminationCommand,
    ) {
        val issuanceId = identifierSource.next()
        issuanceRepository.save(
            CouponIssuanceEntity(
                id = issuanceId,
                campaignId = original.campaignId,
                customerId = original.customerId,
                state = CouponIssuanceState.RESTORED,
                couponExpiresAt = command.terminatedAt.plusSeconds(command.compensationValidityDays.toLong() * 86_400),
                originalIssuanceId = original.id,
                restorationSourceReference = command.sourceReference,
                restorationTrigger = command.trigger.name,
                restorationPolicyVersionId = command.policyVersionId,
            ),
        )
        compensationTermsRepository.save(
            CompensationCouponTermsSnapshotEntity(
                couponIssuanceId = issuanceId,
                campaignId = reservation.campaignId,
                campaignVersion = reservation.campaignVersion,
                storeId = reservation.storeId,
                discountType = reservation.discountType,
                fixedAmountKrw = reservation.fixedAmountKrw,
                rateBps = reservation.rateBps,
                minimumEligibleSubtotalKrw = reservation.minimumEligibleSubtotalKrw,
                maximumDiscountKrw = reservation.maximumDiscountKrw,
                allMenusEligible = reservation.allMenusEligible,
                costBearer = reservation.costBearer,
                platformShareBps = reservation.platformShareBps,
                storeShareBps = reservation.storeShareBps,
                createdAt = command.terminatedAt,
            ),
        )
        compensationEligibleMenuRepository.saveAll(
            reservation.eligibleMenuIds
                .takeIf(String::isNotBlank)
                ?.split(",")
                ?.map(UUID::fromString)
                ?.map { menuId -> CompensationCouponEligibleMenuEntity(identifierSource.next(), issuanceId, menuId) }
                .orEmpty(),
        )
        afterCommit {
            meterRegistry
                .counter(
                    "beanflow.coupon.compensation.issuance.count",
                    "trigger",
                    command.trigger.name.lowercase(),
                    "outcome",
                    "succeeded",
                ).increment()
        }
    }

    private fun calculateDiscount(
        terms: CouponTerms,
        eligibleSubtotal: Long,
    ): Long =
        when (terms.discountType) {
            CouponDiscountType.FIXED_KRW -> {
                minOf(terms.fixedAmountKrw ?: invalidCampaign(), eligibleSubtotal)
            }

            CouponDiscountType.RATE_BPS -> {
                val rate = terms.rateBps ?: invalidCampaign()
                val raw =
                    BigInteger
                        .valueOf(eligibleSubtotal)
                        .multiply(BigInteger.valueOf(rate.toLong()))
                        .divide(BigInteger.valueOf(10_000))
                        .longValueExact()
                terms.maximumDiscountKrw?.let { minOf(raw, it) } ?: raw
            }
        }

    private fun calculateBurden(
        terms: CouponTerms,
        discountKrw: Long,
    ): CouponBurden {
        val costBearer =
            terms.costBearer
                ?: settlementInputUnavailable("Coupon campaign cost bearer is missing")
        val platformShareBps =
            terms.platformShareBps
                ?: settlementInputUnavailable("Coupon campaign platform share is missing")
        val storeShareBps =
            terms.storeShareBps
                ?: settlementInputUnavailable("Coupon campaign store share is missing")
        val validShares =
            when (costBearer) {
                CouponCostBearer.PLATFORM -> {
                    platformShareBps == 10_000 && storeShareBps == 0
                }

                CouponCostBearer.STORE -> {
                    platformShareBps == 0 && storeShareBps == 10_000
                }

                CouponCostBearer.SHARED -> {
                    platformShareBps > 0 &&
                        storeShareBps > 0 &&
                        platformShareBps + storeShareBps == 10_000
                }
            }
        if (!validShares) {
            settlementInputUnavailable("Coupon campaign cost shares are invalid")
        }
        val storeCouponCostKrw =
            BigInteger
                .valueOf(discountKrw)
                .multiply(BigInteger.valueOf(storeShareBps.toLong()))
                .divide(BigInteger.valueOf(10_000))
                .longValueExact()
        return CouponBurden(
            costBearer = costBearer,
            platformShareBps = platformShareBps,
            storeShareBps = storeShareBps,
            platformCouponCostKrw = discountKrw - storeCouponCostKrw,
            storeCouponCostKrw = storeCouponCostKrw,
        )
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
            campaignId = campaignId,
            campaignVersion = campaignVersion,
            costBearer = costBearer,
            platformShareBps = platformShareBps,
            storeShareBps = storeShareBps,
            platformCouponCostKrw = platformCouponCostKrw,
            storeCouponCostKrw = storeCouponCostKrw,
        )

    private fun invalidCampaign(): Nothing = fail(FailureCode.COUPON_NOT_AVAILABLE, "Coupon campaign discount fields are invalid")

    private fun settlementInputUnavailable(message: String): Nothing = fail(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message)

    private fun recordRestorationMetric(
        command: RestoreCouponAfterTerminationCommand,
        disposition: CouponRestorationDisposition,
        restoredAmountKrw: Long,
    ) {
        afterCommit {
            val tags =
                arrayOf(
                    "benefit_type",
                    "coupon",
                    "trigger",
                    command.trigger.name.lowercase(),
                    "disposition",
                    disposition.name.lowercase(),
                )
            meterRegistry.counter("beanflow.benefit.restoration.count", *tags).increment()
            meterRegistry.summary("beanflow.benefit.restoration.amount", *tags).record(restoredAmountKrw.toDouble())
        }
    }

    private fun sourceConflict(
        command: RestoreCouponAfterTerminationCommand,
        message: String,
    ): Nothing {
        meterRegistry
            .counter(
                "beanflow.benefit.restoration.source_conflict.count",
                "benefit_type",
                "coupon",
                "trigger",
                command.trigger.name.lowercase(),
            ).increment()
        fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, message)
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private data class CouponBurden(
        val costBearer: CouponCostBearer,
        val platformShareBps: Int,
        val storeShareBps: Int,
        val platformCouponCostKrw: Long,
        val storeCouponCostKrw: Long,
    )

    private data class CouponTerms(
        val campaignId: UUID,
        val campaignVersion: Long,
        val storeId: UUID,
        val discountType: CouponDiscountType,
        val fixedAmountKrw: Long?,
        val rateBps: Int?,
        val minimumEligibleSubtotalKrw: Long,
        val maximumDiscountKrw: Long?,
        val allMenusEligible: Boolean,
        val eligibleMenuIds: Set<UUID>,
        val costBearer: CouponCostBearer?,
        val platformShareBps: Int?,
        val storeShareBps: Int?,
    )
}
