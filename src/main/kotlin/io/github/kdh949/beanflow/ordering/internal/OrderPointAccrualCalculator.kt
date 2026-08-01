package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class OrderPointAccrualLineInput(
    val orderLineId: UUID,
    val lineSequence: Int,
    val unitPriceKrw: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponDiscountKrw: Long,
    val pointsAppliedKrw: Long,
    val cashPayableKrw: Long,
)

internal data class OrderPointAccrualUnitResult(
    val orderLineId: UUID,
    val lineSequence: Int,
    val unitPosition: Int,
    val cashPayableKrw: Long,
    val accruedAmountKrw: Long,
)

internal data class OrderPointAccrualCalculation(
    val grossAccrualAmountKrw: Long,
    val units: List<OrderPointAccrualUnitResult>,
)

internal class OrderPointAccrualCalculator {
    fun calculate(
        policy: OrdinaryPointAccrualPolicySnapshot,
        lines: List<OrderPointAccrualLineInput>,
    ): OrderPointAccrualCalculation {
        require(lines.isNotEmpty()) { "At least one accrual line is required" }
        require(lines.map { it.lineSequence } == lines.indices.toList()) {
            "Accrual line sequences must be contiguous"
        }
        val units = lines.flatMap(::conceptualUnits)
        val payable = exactSum(units.map { it.cashPayableKrw })
        val gross = round(BigInteger.valueOf(payable).multiply(BigInteger.valueOf(policy.accrualRateBps.toLong())), policy)
        val allocations = allocate(gross, payable, units)
        return OrderPointAccrualCalculation(
            grossAccrualAmountKrw = gross,
            units = units.mapIndexed { index, unit -> unit.copy(accruedAmountKrw = allocations[index]) },
        ).also { calculation ->
            check(calculation.units.sumOf { it.accruedAmountKrw } == calculation.grossAccrualAmountKrw)
        }
    }

    fun expiresAt(
        policy: OrdinaryPointAccrualPolicySnapshot,
        completedAt: Instant,
    ): Instant =
        when (policy.expiryRule) {
            OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION -> {
                completedAt.plus(Duration.ofDays(policy.validityDays.toLong()))
            }

            OrdinaryPointAccrualExpiryRule.SEOUL_CALENDAR_DAYS_FROM_COMPLETION -> {
                completedAt
                    .atZone(SEOUL)
                    .toLocalDate()
                    .plusDays(policy.validityDays.toLong())
                    .atStartOfDay(SEOUL)
                    .toInstant()
            }
        }

    private fun conceptualUnits(line: OrderPointAccrualLineInput): List<OrderPointAccrualUnitResult> {
        require(line.quantity in 1..Int.MAX_VALUE.toLong()) { "OrderLine quantity is invalid" }
        require(line.unitPriceKrw >= 0 && line.couponDiscountKrw >= 0 && line.pointsAppliedKrw >= 0 && line.cashPayableKrw >= 0) {
            "OrderLine monetary values must be non-negative"
        }
        val expectedGross = BigInteger.valueOf(line.unitPriceKrw).multiply(BigInteger.valueOf(line.quantity))
        require(expectedGross == BigInteger.valueOf(line.grossKrw)) { "OrderLine gross snapshot is inconsistent" }
        require(
            BigInteger
                .valueOf(line.couponDiscountKrw)
                .add(BigInteger.valueOf(line.pointsAppliedKrw))
                .add(BigInteger.valueOf(line.cashPayableKrw)) == expectedGross,
        ) { "OrderLine pricing snapshot does not tie out" }

        val quantity = line.quantity.toInt()
        val couponBase = line.couponDiscountKrw / quantity
        val couponRemainder = (line.couponDiscountKrw % quantity).toInt()
        val postCoupon =
            LongArray(quantity) { index ->
                line.unitPriceKrw - couponBase - if (index < couponRemainder) 1 else 0
            }
        require(postCoupon.all { it >= 0 }) { "OrderLine coupon exceeds a conceptual unit" }
        val postCouponTotal = exactSum(postCoupon.asIterable())
        require(line.pointsAppliedKrw <= postCouponTotal) { "OrderLine points exceed post-coupon value" }

        val points = LongArray(quantity)
        if (line.pointsAppliedKrw > 0) {
            val basis = BigInteger.valueOf(postCouponTotal)
            postCoupon.forEachIndexed { index, amount ->
                points[index] =
                    BigInteger
                        .valueOf(line.pointsAppliedKrw)
                        .multiply(BigInteger.valueOf(amount))
                        .divide(basis)
                        .longValueExact()
            }
            var remainder = line.pointsAppliedKrw - exactSum(points.asIterable())
            postCoupon.indices
                .sortedWith(compareByDescending<Int> { postCoupon[it] }.thenBy { it })
                .forEach { index ->
                    if (remainder > 0 && points[index] < postCoupon[index]) {
                        points[index]++
                        remainder--
                    }
                }
            check(remainder == 0L) { "OrderLine point allocation did not tie out" }
        }
        return postCoupon.indices
            .map { index ->
                OrderPointAccrualUnitResult(
                    orderLineId = line.orderLineId,
                    lineSequence = line.lineSequence,
                    unitPosition = index,
                    cashPayableKrw = postCoupon[index] - points[index],
                    accruedAmountKrw = 0,
                )
            }.also { result ->
                require(exactSum(result.map { it.cashPayableKrw }) == line.cashPayableKrw) {
                    "OrderLine conceptual-unit cash does not tie out"
                }
            }
    }

    private fun round(
        numerator: BigInteger,
        policy: OrdinaryPointAccrualPolicySnapshot,
    ): Long {
        val (quotient, remainder) = numerator.divideAndRemainder(BPS_DIVISOR)
        val rounded =
            when (policy.roundingMode) {
                PointAccrualRoundingMode.FLOOR -> {
                    quotient
                }

                PointAccrualRoundingMode.HALF_UP -> {
                    if (remainder >= BPS_HALF) quotient.add(BigInteger.ONE) else quotient
                }
            }
        return rounded.longValueExact()
    }

    private fun allocate(
        gross: Long,
        payable: Long,
        units: List<OrderPointAccrualUnitResult>,
    ): LongArray {
        if (gross == 0L) return LongArray(units.size)
        require(payable > 0) { "Positive accrual requires positive payable amount" }
        val basis = BigInteger.valueOf(payable)
        val result =
            LongArray(units.size) { index ->
                BigInteger
                    .valueOf(gross)
                    .multiply(BigInteger.valueOf(units[index].cashPayableKrw))
                    .divide(basis)
                    .longValueExact()
            }
        var remainder = gross - exactSum(result.asIterable())
        units.indices
            .filter { units[it].cashPayableKrw > 0 }
            .sortedWith(
                compareByDescending<Int> { units[it].cashPayableKrw }
                    .thenBy { units[it].lineSequence }
                    .thenBy { units[it].unitPosition },
            ).forEach { index ->
                if (remainder > 0) {
                    result[index]++
                    remainder--
                }
            }
        check(remainder == 0L) { "Accrual unit allocation did not tie out" }
        return result
    }

    private fun exactSum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)

    private companion object {
        val BPS_DIVISOR: BigInteger = BigInteger.valueOf(10_000)
        val BPS_HALF: BigInteger = BigInteger.valueOf(5_000)
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
