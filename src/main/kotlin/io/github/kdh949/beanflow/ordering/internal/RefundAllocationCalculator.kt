package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.RefundableOrderLineSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * One refundable unit of an OrderLine. `grossKrw` is the unit price and the
 * coupon and point shares are the deterministic allocation of the line totals.
 */
internal data class RefundUnitAllocation(
    val grossKrw: Long,
    val couponKrw: Long,
    val pointsKrw: Long,
) {
    val cashKrw: Long = grossKrw - couponKrw - pointsKrw
}

/** The contiguous unit range of one OrderLine selected by a refund request. */
internal data class RefundLineAllocation(
    val line: RefundableOrderLineSnapshot,
    val firstUnitIndex: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponKrw: Long,
    val pointsKrw: Long,
    val cashKrw: Long,
)

/**
 * Pure refund allocation. The preview projection and the refund preparation
 * transaction call the same functions so a previewed amount and an executed
 * amount can only differ when the underlying snapshot itself changed.
 *
 * Nothing here reads or writes state; every input is an immutable snapshot.
 */
internal object RefundAllocationCalculator {
    /**
     * Splits one OrderLine into its units. Coupon value is spread by remainder
     * first, points by post-coupon balance, and the result must tie out against
     * the stored line totals.
     */
    fun unitAllocations(line: RefundableOrderLineSnapshot): List<RefundUnitAllocation> {
        if (line.quantity > Int.MAX_VALUE) fail(FailureCode.INVALID_REQUEST, "OrderLine quantity is too large")
        if (Math.multiplyExact(line.unitPriceKrw, line.quantity) != line.grossKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine gross snapshot is inconsistent")
        }
        val quantity = line.quantity.toInt()
        val couponBase = line.couponDiscountKrw / quantity
        val couponRemainder = (line.couponDiscountKrw % quantity).toInt()
        val coupons = List(quantity) { index -> couponBase + if (index < couponRemainder) 1 else 0 }
        val balances = coupons.map { line.unitPriceKrw - it }
        val balanceTotal = balances.sum()
        if (balanceTotal < line.pointsAppliedKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine points exceed its post-coupon balance")
        }
        val points = LongArray(quantity)
        if (line.pointsAppliedKrw > 0) {
            balances.forEachIndexed { index, balance ->
                points[index] = Math.multiplyExact(line.pointsAppliedKrw, balance) / balanceTotal
            }
            var remainder = line.pointsAppliedKrw - points.sum()
            balances.indices.sortedWith(compareByDescending<Int> { balances[it] }.thenBy { it }).forEach { index ->
                if (remainder > 0 && points[index] < balances[index]) {
                    points[index]++
                    remainder--
                }
            }
            if (remainder != 0L) fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine point remainder did not tie out")
        }
        return balances.indices
            .map { index -> RefundUnitAllocation(line.unitPriceKrw, coupons[index], points[index]) }
            .also { units ->
                if (units.sumOf { it.couponKrw } != line.couponDiscountKrw ||
                    units.sumOf { it.pointsKrw } != line.pointsAppliedKrw ||
                    units.sumOf { it.cashKrw } != line.cashPayableKrw
                ) {
                    fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine unit allocation did not tie out")
                }
            }
    }

    /** Units of [line] that no successful Refund has consumed yet. */
    fun remainingQuantity(
        line: RefundableOrderLineSnapshot,
        consumed: Map<UUID, Long>,
    ): Long {
        val used = consumed[line.orderLineId] ?: 0L
        if (used > line.quantity) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Successful Refund quantity exceeds OrderLine snapshot")
        }
        return line.quantity - used
    }

    /**
     * Allocates the requested units of each line.
     *
     * [requestedByOrderLine] `null` selects every remaining unit, which only the
     * legacy UUID refund contract uses. The merchant contract always sends an
     * explicit selection.
     */
    fun allocate(
        lines: List<RefundableOrderLineSnapshot>,
        consumed: Map<UUID, Long>,
        requestedByOrderLine: Map<UUID, Long>?,
    ): List<RefundLineAllocation> {
        if (requestedByOrderLine != null && requestedByOrderLine.keys.any { id -> lines.none { it.orderLineId == id } }) {
            fail(FailureCode.INVALID_REQUEST, "Refund contains an OrderLine from another order")
        }
        return lines.mapNotNull { line ->
            val first = consumed[line.orderLineId] ?: 0L
            val remaining = remainingQuantity(line, consumed)
            val requested =
                requestedByOrderLine?.get(line.orderLineId)
                    ?: remaining.takeIf { requestedByOrderLine == null }
                    ?: 0L
            if (requested == 0L) return@mapNotNull null
            if (requested < 1 || requested > remaining) {
                fail(
                    FailureCode.REFUND_QUANTITY_UNAVAILABLE,
                    "Refund quantity exceeds remaining OrderLine units",
                )
            }
            val units = unitAllocations(line).subList(first.toInt(), Math.addExact(first, requested).toInt())
            RefundLineAllocation(
                line = line,
                firstUnitIndex = first,
                quantity = requested,
                grossKrw = units.sumOf { it.grossKrw },
                couponKrw = units.sumOf { it.couponKrw },
                pointsKrw = units.sumOf { it.pointsKrw },
                cashKrw = units.sumOf { it.cashKrw },
            )
        }
    }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}

/**
 * Canonical refund state hash of [ADR-108](docs/adr/ADR-108-merchant-partial-refund-preview.md).
 *
 * It changes whenever anything the preview amounts depend on changes, so an
 * execution can detect that its preview is stale. It is not an authorization
 * token: membership is always re-checked on execution.
 */
internal object RefundPreviewVersion {
    fun compute(state: RefundPreviewState): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(state.canonical().toByteArray(StandardCharsets.UTF_8)),
        )

    private fun RefundPreviewState.canonical(): String =
        buildString {
            append("beanflow.refund-preview.v1")
            append("|order=").append(orderId).append(':').append(orderAggregateVersion)
            append("|payment=").append(paymentId).append(':').append(paymentVersion)
            append("|approved=").append(approvedAmountKrw)
            append("|succeeded=").append(succeededRefundAmountKrw)
            append("|unresolved=").append(unresolvedRefundCount)
            append("|policy=").append(restorationPolicyVersionId)
            append("|lines=")
            remainingByLineSequence
                .toSortedMap()
                .entries
                .joinTo(this, ",") { (sequence, remaining) -> "$sequence:$remaining" }
        }
}

/** Everything the previewed amounts depend on. */
internal data class RefundPreviewState(
    val orderId: UUID,
    val orderAggregateVersion: Long,
    val paymentId: UUID,
    val paymentVersion: Long,
    val approvedAmountKrw: Long,
    val succeededRefundAmountKrw: Long,
    val unresolvedRefundCount: Int,
    val restorationPolicyVersionId: Long,
    val remainingByLineSequence: Map<Int, Long>,
)
