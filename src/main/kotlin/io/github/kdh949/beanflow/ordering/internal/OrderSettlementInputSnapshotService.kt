package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.loyalty.api.PointReservationAllocation
import io.github.kdh949.beanflow.loyalty.api.PointReservationResult
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshot
import io.github.kdh949.beanflow.ordering.api.OrderSettlementInputSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.SettlementCouponCostBearer
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.promotion.api.CouponCostBearer
import io.github.kdh949.beanflow.promotion.api.CouponReservationQuote
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
internal class OrderSettlementInputSnapshotService(
    private val repository: OrderSettlementInputSnapshotJpaRepository,
    private val meterRegistry: MeterRegistry,
) : OrderSettlementInputSnapshotOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    fun materialize(
        order: Order,
        terms: StoreSettlementTermsSnapshot,
        coupon: CouponReservationQuote?,
        points: PointReservationResult?,
        createdAt: Instant,
    ): OrderSettlementInputSnapshot {
        val candidate = calculate(order, terms, coupon, points, databaseInstant(createdAt))
        repository.findById(order.id).orElse(null)?.let { existing ->
            validate(existing)
            if (existing.canonicalSnapshotHash != candidate.canonicalSnapshotHash) {
                tieOutFailure("HASH", "Existing settlement input snapshot conflicts with the order-derived snapshot")
            }
            metric("REPLAYED")
            return existing.toSnapshot()
        }
        return try {
            val saved = repository.saveAndFlush(candidate)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        metric(if (status == TransactionSynchronization.STATUS_COMMITTED) "SAVED" else "ROLLED_BACK")
                    }
                },
            )
            saved.toSnapshot()
        } catch (failure: DataAccessException) {
            unavailable("PERSISTENCE", "Settlement input snapshot could not be persisted", failure)
        }
    }

    @Transactional(readOnly = true)
    override fun read(orderId: UUID): OrderSettlementInputSnapshot =
        try {
            val entity =
                repository.findById(orderId).orElseThrow {
                    unavailable("SNAPSHOT", "Settlement input snapshot is missing")
                }
            validate(entity)
            metric("READ")
            entity.toSnapshot()
        } catch (failure: DataAccessException) {
            unavailable("PERSISTENCE", "Settlement input snapshot could not be read", failure)
        }

    private fun calculate(
        order: Order,
        terms: StoreSettlementTermsSnapshot,
        coupon: CouponReservationQuote?,
        points: PointReservationResult?,
        createdAt: Instant,
    ): OrderSettlementInputSnapshotEntity {
        validateTerms(order, terms, createdAt)
        val couponSource = couponSource(order, coupon)
        val pointSource = pointSource(order, points)
        val feeKrw = floorBasisPoints(order.payableKrw, terms.feeRateBps, "FEE")
        val benefitCostKrw = exactAdd(couponSource.storeCostKrw, pointSource.storeCostKrw, "BENEFIT")
        val netSettlementKrw =
            exactSubtract(
                exactSubtract(order.subtotalKrw, feeKrw, "NET"),
                benefitCostKrw,
                "NET",
            )
        if (netSettlementKrw < 0) {
            tieOutFailure("NET", "Settlement input produces a negative net settlement")
        }
        val withoutHash =
            OrderSettlementInputSnapshotEntity(
                orderId = order.id,
                storeId = order.storeId,
                storeSettlementTermsVersionId = terms.termsVersionId,
                storeSettlementTermsSourceReference = terms.sourceReference,
                couponReservationId = couponSource.reservationId,
                couponCampaignId = couponSource.campaignId,
                couponCampaignVersion = couponSource.campaignVersion,
                couponCostBearer = couponSource.costBearer,
                couponPlatformShareBps = couponSource.platformShareBps,
                couponStoreShareBps = couponSource.storeShareBps,
                couponDiscountKrw = order.couponDiscountKrw,
                platformCouponCostKrw = couponSource.platformCostKrw,
                couponCostKrw = couponSource.storeCostKrw,
                pointReservationId = pointSource.reservationId,
                pointAllocationHash = pointSource.allocationHash,
                pointsAppliedKrw = order.pointsAppliedKrw,
                pointCostKrw = pointSource.storeCostKrw,
                grossPaidKrw = order.subtotalKrw,
                feeBaseKrw = order.payableKrw,
                feeRateBps = terms.feeRateBps,
                feeKrw = feeKrw,
                benefitCostKrw = benefitCostKrw,
                netSettlementKrw = netSettlementKrw,
                currency = "KRW",
                snapshotSchemaVersion = SNAPSHOT_SCHEMA_VERSION,
                canonicalSnapshotHash = "",
                createdAt = createdAt,
            )
        val entity = OrderSettlementInputSnapshotCanonicalizer.canonicalize(withoutHash)
        validate(entity)
        return entity
    }

    private fun validateTerms(
        order: Order,
        terms: StoreSettlementTermsSnapshot,
        createdAt: Instant,
    ) {
        if (terms.storeId != order.storeId ||
            terms.sourceReference.isBlank() ||
            terms.feeRateBps !in 0..10_000 ||
            createdAt.isBefore(terms.effectiveFrom) ||
            (terms.effectiveTo != null && !createdAt.isBefore(terms.effectiveTo))
        ) {
            unavailable("STORE_TERMS", "Store settlement terms do not apply to the order")
        }
    }

    private fun couponSource(
        order: Order,
        coupon: CouponReservationQuote?,
    ): CouponSource {
        if (coupon == null) {
            if (order.couponDiscountKrw != 0L) {
                unavailable("COUPON", "Order coupon discount has no reservation snapshot")
            }
            return CouponSource.empty()
        }
        val bearer = SettlementCouponCostBearer.valueOf(coupon.costBearer.name)
        val validShares =
            when (coupon.costBearer) {
                CouponCostBearer.PLATFORM -> {
                    coupon.platformShareBps == 10_000 && coupon.storeShareBps == 0
                }

                CouponCostBearer.STORE -> {
                    coupon.platformShareBps == 0 && coupon.storeShareBps == 10_000
                }

                CouponCostBearer.SHARED -> {
                    coupon.platformShareBps > 0 &&
                        coupon.storeShareBps > 0 &&
                        coupon.platformShareBps + coupon.storeShareBps == 10_000
                }
            }
        val expectedStoreCost = floorBasisPoints(coupon.discountKrw, coupon.storeShareBps, "COUPON")
        if (coupon.discountKrw != order.couponDiscountKrw ||
            coupon.campaignVersion < 0 ||
            !validShares ||
            coupon.storeCouponCostKrw != expectedStoreCost ||
            exactAdd(coupon.platformCouponCostKrw, coupon.storeCouponCostKrw, "COUPON") != coupon.discountKrw
        ) {
            tieOutFailure("COUPON", "Coupon reservation cost snapshot does not tie out")
        }
        return CouponSource(
            reservationId = coupon.reservationId,
            campaignId = coupon.campaignId,
            campaignVersion = coupon.campaignVersion,
            costBearer = bearer,
            platformShareBps = coupon.platformShareBps,
            storeShareBps = coupon.storeShareBps,
            platformCostKrw = coupon.platformCouponCostKrw,
            storeCostKrw = coupon.storeCouponCostKrw,
        )
    }

    private fun pointSource(
        order: Order,
        points: PointReservationResult?,
    ): PointSource {
        if (points == null) {
            if (order.pointsAppliedKrw != 0L) {
                unavailable("POINT", "Order point amount has no reservation allocation snapshot")
            }
            return PointSource.empty()
        }
        if (order.pointsAppliedKrw <= 0 || points.allocations.isEmpty()) {
            unavailable("POINT", "Point reservation allocation snapshot is incomplete")
        }
        if (points.allocations
                .map(PointReservationAllocation::pointLotId)
                .toSet()
                .size != points.allocations.size
        ) {
            tieOutFailure("POINT", "Point reservation contains duplicate lot allocations")
        }
        val allocationTotal = exactSum(points.allocations.map(PointReservationAllocation::finalAllocationKrw), "POINT")
        if (allocationTotal != order.pointsAppliedKrw) {
            tieOutFailure("POINT", "Point reservation allocations do not match applied points")
        }
        val storeAllocations = points.allocations.filter { it.issuerType == PointIssuerType.STORE }
        if (storeAllocations.any { it.issuerReference != order.storeId.toString() }) {
            unavailable("POINT", "Store point issuer does not match the order store")
        }
        return PointSource(
            reservationId = points.reservationId,
            allocationHash = pointAllocationHash(points),
            storeCostKrw = exactSum(storeAllocations.map(PointReservationAllocation::finalAllocationKrw), "POINT"),
        )
    }

    private fun validate(entity: OrderSettlementInputSnapshotEntity) {
        if (entity.storeSettlementTermsSourceReference.isBlank() ||
            entity.feeRateBps !in 0..10_000 ||
            entity.snapshotSchemaVersion != SNAPSHOT_SCHEMA_VERSION ||
            entity.currency != "KRW" ||
            listOf(
                entity.couponDiscountKrw,
                entity.platformCouponCostKrw,
                entity.couponCostKrw,
                entity.pointsAppliedKrw,
                entity.pointCostKrw,
                entity.grossPaidKrw,
                entity.feeBaseKrw,
                entity.feeKrw,
                entity.benefitCostKrw,
                entity.netSettlementKrw,
            ).any { it < 0 }
        ) {
            tieOutFailure("SOURCE", "Settlement input snapshot contains invalid required values")
        }
        validateCouponEntity(entity)
        validatePointEntity(entity)
        if (entity.feeBaseKrw !=
            exactSubtract(
                exactSubtract(entity.grossPaidKrw, entity.couponDiscountKrw, "ORDER"),
                entity.pointsAppliedKrw,
                "ORDER",
            )
        ) {
            tieOutFailure("ORDER", "Settlement input amounts do not match order pricing")
        }
        if (entity.feeKrw != floorBasisPoints(entity.feeBaseKrw, entity.feeRateBps, "FEE")) {
            tieOutFailure("FEE", "Settlement fee does not match its immutable basis")
        }
        if (entity.benefitCostKrw != exactAdd(entity.couponCostKrw, entity.pointCostKrw, "BENEFIT")) {
            tieOutFailure("BENEFIT", "Settlement benefit cost does not match its cost legs")
        }
        val expectedNet =
            exactSubtract(
                exactSubtract(entity.grossPaidKrw, entity.feeKrw, "NET"),
                entity.benefitCostKrw,
                "NET",
            )
        if (expectedNet < 0 || entity.netSettlementKrw != expectedNet) {
            tieOutFailure("NET", "Settlement net amount does not tie out")
        }
        if (!OrderSettlementInputSnapshotCanonicalizer.matches(entity)) {
            tieOutFailure("HASH", "Settlement input snapshot hash does not match its immutable fields")
        }
    }

    private fun validateCouponEntity(entity: OrderSettlementInputSnapshotEntity) {
        if (entity.couponDiscountKrw == 0L) {
            if (listOf(
                    entity.couponReservationId,
                    entity.couponCampaignId,
                    entity.couponCampaignVersion,
                    entity.couponCostBearer,
                    entity.couponPlatformShareBps,
                    entity.couponStoreShareBps,
                ).any { it != null } ||
                entity.platformCouponCostKrw != 0L ||
                entity.couponCostKrw != 0L
            ) {
                tieOutFailure("COUPON", "Zero coupon discount contains a coupon source")
            }
            return
        }
        val bearer = entity.couponCostBearer ?: tieOutFailure("COUPON", "Coupon bearer is missing")
        val platformShare = entity.couponPlatformShareBps ?: tieOutFailure("COUPON", "Coupon platform share is missing")
        val storeShare = entity.couponStoreShareBps ?: tieOutFailure("COUPON", "Coupon store share is missing")
        if (entity.couponReservationId == null || entity.couponCampaignId == null || entity.couponCampaignVersion == null) {
            tieOutFailure("COUPON", "Coupon source identifiers are missing")
        }
        val validShares =
            when (bearer) {
                SettlementCouponCostBearer.PLATFORM -> platformShare == 10_000 && storeShare == 0
                SettlementCouponCostBearer.STORE -> platformShare == 0 && storeShare == 10_000
                SettlementCouponCostBearer.SHARED -> platformShare > 0 && storeShare > 0 && platformShare + storeShare == 10_000
            }
        if (!validShares ||
            entity.couponCostKrw != floorBasisPoints(entity.couponDiscountKrw, storeShare, "COUPON") ||
            exactAdd(entity.platformCouponCostKrw, entity.couponCostKrw, "COUPON") != entity.couponDiscountKrw
        ) {
            tieOutFailure("COUPON", "Coupon source and final cost legs do not tie out")
        }
    }

    private fun validatePointEntity(entity: OrderSettlementInputSnapshotEntity) {
        if (entity.pointsAppliedKrw == 0L) {
            if (entity.pointReservationId != null || entity.pointAllocationHash != null || entity.pointCostKrw != 0L) {
                tieOutFailure("POINT", "Zero applied points contain a point source")
            }
        } else if (entity.pointReservationId == null ||
            entity.pointAllocationHash?.matches(HASH_PATTERN) != true ||
            entity.pointCostKrw > entity.pointsAppliedKrw
        ) {
            tieOutFailure("POINT", "Point source or store cost is invalid")
        }
    }

    private fun pointAllocationHash(points: PointReservationResult): String {
        val canonical = CanonicalFields()
        canonical.add(points.reservationId)
        points.allocations.sortedBy(PointReservationAllocation::pointLotId).forEach { allocation ->
            canonical.add(allocation.pointLotId)
            canonical.add(allocation.issuerType)
            canonical.add(allocation.issuerReference)
            canonical.add(allocation.finalAllocationKrw)
        }
        return sha256(canonical.toString())
    }

    private fun OrderSettlementInputSnapshotEntity.toSnapshot() =
        OrderSettlementInputSnapshot(
            orderId,
            storeId,
            storeSettlementTermsVersionId,
            storeSettlementTermsSourceReference,
            couponReservationId,
            couponCampaignId,
            couponCampaignVersion,
            couponCostBearer,
            couponPlatformShareBps,
            couponStoreShareBps,
            couponDiscountKrw,
            platformCouponCostKrw,
            couponCostKrw,
            pointReservationId,
            pointAllocationHash,
            pointsAppliedKrw,
            pointCostKrw,
            grossPaidKrw,
            feeBaseKrw,
            feeRateBps,
            feeKrw,
            benefitCostKrw,
            netSettlementKrw,
            currency,
            snapshotSchemaVersion,
            canonicalSnapshotHash,
            createdAt,
        )

    private fun floorBasisPoints(
        amountKrw: Long,
        rateBps: Int,
        reason: String,
    ): Long {
        if (amountKrw < 0 || rateBps !in 0..10_000) {
            tieOutFailure(reason, "Settlement basis-point input is outside the supported range")
        }
        return BigInteger
            .valueOf(amountKrw)
            .multiply(BigInteger.valueOf(rateBps.toLong()))
            .divide(TEN_THOUSAND)
            .longValueExact()
    }

    private fun exactSum(
        values: Iterable<Long>,
        reason: String,
    ): Long = values.fold(0L) { total, value -> exactAdd(total, value, reason) }

    private fun exactAdd(
        left: Long,
        right: Long,
        reason: String,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (failure: ArithmeticException) {
            tieOutFailure(reason, "Settlement input amount overflowed", failure)
        }

    private fun exactSubtract(
        left: Long,
        right: Long,
        reason: String,
    ): Long =
        try {
            Math.subtractExact(left, right)
        } catch (failure: ArithmeticException) {
            tieOutFailure(reason, "Settlement input amount overflowed", failure)
        }

    private fun metric(outcome: String) {
        meterRegistry.counter("beanflow.settlement.input.snapshot.count", "outcome", outcome).increment()
    }

    private fun unavailable(
        source: String,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        meterRegistry.counter("beanflow.settlement.input.unavailable.count", "source", source).increment()
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message).also { cause?.let(it::initCause) }
    }

    private fun tieOutFailure(
        reason: String,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        meterRegistry.counter("beanflow.settlement.input.tie_out.failure.count", "reason", reason).increment()
        throw DomainFailure(FailureCode.SETTLEMENT_INPUT_UNAVAILABLE, message).also { cause?.let(it::initCause) }
    }

    private data class CouponSource(
        val reservationId: UUID?,
        val campaignId: UUID?,
        val campaignVersion: Long?,
        val costBearer: SettlementCouponCostBearer?,
        val platformShareBps: Int?,
        val storeShareBps: Int?,
        val platformCostKrw: Long,
        val storeCostKrw: Long,
    ) {
        companion object {
            fun empty() = CouponSource(null, null, null, null, null, null, 0, 0)
        }
    }

    private data class PointSource(
        val reservationId: UUID?,
        val allocationHash: String?,
        val storeCostKrw: Long,
    ) {
        companion object {
            fun empty() = PointSource(null, null, 0)
        }
    }

    private companion object {
        const val SNAPSHOT_SCHEMA_VERSION = 1
        val TEN_THOUSAND: BigInteger = BigInteger.valueOf(10_000)
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Single canonical representation for settlement input snapshot persistence and integrity reads.
 * Local bootstrap fixtures use this owner implementation instead of manufacturing a hash that only
 * satisfies the database shape.
 */
internal object OrderSettlementInputSnapshotCanonicalizer {
    fun canonicalize(entity: OrderSettlementInputSnapshotEntity): OrderSettlementInputSnapshotEntity {
        val databaseEntity = entity.withCanonicalHash(entity.canonicalSnapshotHash, databaseInstant(entity.createdAt))
        return databaseEntity.withCanonicalHash(canonicalHash(databaseEntity))
    }

    fun matches(entity: OrderSettlementInputSnapshotEntity): Boolean = entity.canonicalSnapshotHash == canonicalHash(entity)

    private fun canonicalHash(entity: OrderSettlementInputSnapshotEntity): String {
        val canonical = CanonicalFields()
        canonical.add(entity.snapshotSchemaVersion)
        canonical.add(entity.orderId)
        canonical.add(entity.storeId)
        canonical.add(entity.storeSettlementTermsVersionId)
        canonical.add(entity.storeSettlementTermsSourceReference)
        canonical.add(entity.couponReservationId)
        canonical.add(entity.couponCampaignId)
        canonical.add(entity.couponCampaignVersion)
        canonical.add(entity.couponCostBearer)
        canonical.add(entity.couponPlatformShareBps)
        canonical.add(entity.couponStoreShareBps)
        canonical.add(entity.couponDiscountKrw)
        canonical.add(entity.platformCouponCostKrw)
        canonical.add(entity.couponCostKrw)
        canonical.add(entity.pointReservationId)
        canonical.add(entity.pointAllocationHash)
        canonical.add(entity.pointsAppliedKrw)
        canonical.add(entity.pointCostKrw)
        canonical.add(entity.grossPaidKrw)
        canonical.add(entity.feeBaseKrw)
        canonical.add(entity.feeRateBps)
        canonical.add(entity.feeKrw)
        canonical.add(entity.benefitCostKrw)
        canonical.add(entity.netSettlementKrw)
        canonical.add(entity.currency)
        canonical.add(entity.createdAt.epochSecond)
        canonical.add(entity.createdAt.nano / 1_000)
        return sha256(canonical.toString())
    }

    private fun OrderSettlementInputSnapshotEntity.withCanonicalHash(
        hash: String,
        createdAt: Instant = this.createdAt,
    ) = OrderSettlementInputSnapshotEntity(
        orderId,
        storeId,
        storeSettlementTermsVersionId,
        storeSettlementTermsSourceReference,
        couponReservationId,
        couponCampaignId,
        couponCampaignVersion,
        couponCostBearer,
        couponPlatformShareBps,
        couponStoreShareBps,
        couponDiscountKrw,
        platformCouponCostKrw,
        couponCostKrw,
        pointReservationId,
        pointAllocationHash,
        pointsAppliedKrw,
        pointCostKrw,
        grossPaidKrw,
        feeBaseKrw,
        feeRateBps,
        feeKrw,
        benefitCostKrw,
        netSettlementKrw,
        currency,
        snapshotSchemaVersion,
        hash,
        createdAt,
    )
}

private fun databaseInstant(value: Instant): Instant =
    ((value.nano.toLong() + 500) / 1_000).let { roundedMicros ->
        if (roundedMicros == 1_000_000L) {
            Instant.ofEpochSecond(value.epochSecond + 1)
        } else {
            Instant.ofEpochSecond(value.epochSecond, roundedMicros * 1_000)
        }
    }

private class CanonicalFields {
    private val value = StringBuilder()

    fun add(field: Any?) {
        val text = field?.toString() ?: "<null>"
        value.append(text.length).append(':').append(text)
    }

    override fun toString(): String = value.toString()
}

private fun sha256(value: String): String =
    HexFormat
        .of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
