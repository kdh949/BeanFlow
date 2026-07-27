package io.github.kdh949.beanflow.ordering.internal.domain

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.math.BigInteger
import java.util.UUID

data class PricingLine(
	val lineSequence: Int,
	val menuId: UUID,
	val unitPrice: Krw,
	val quantity: Long,
	val couponEligible: Boolean,
)

data class PricedLine(
	val lineSequence: Int,
	val menuId: UUID,
	val unitPrice: Krw,
	val quantity: Long,
	val gross: Krw,
	val couponDiscount: Krw,
	val pointsApplied: Krw,
	val cashPayable: Krw,
)

data class OrderPricing(
	val lines: List<PricedLine>,
	val subtotal: Krw,
	val couponDiscount: Krw,
	val pointsApplied: Krw,
	val payable: Krw,
)

class OrderPricingCalculator {

	fun calculate(
		lines: List<PricingLine>,
		couponDiscount: Krw,
		pointsToUse: Krw,
	): OrderPricing {
		validateLines(lines)
		val grossBySequence = lines.associate { line ->
			line.lineSequence to line.unitPrice.multiply(line.quantity)
		}
		val subtotal = sum(grossBySequence.values)
		val eligibleSubtotal = sum(
			lines.filter(PricingLine::couponEligible).map { grossBySequence.getValue(it.lineSequence) },
		)
		if (couponDiscount > eligibleSubtotal) {
			fail("Coupon discount exceeds eligible line subtotal")
		}

		val couponBySequence = allocate(
			total = couponDiscount,
			bases = lines
				.filter(PricingLine::couponEligible)
				.map { AllocationBase(it.lineSequence, grossBySequence.getValue(it.lineSequence)) },
		)
		val postCouponBySequence = lines.associate { line ->
			val gross = grossBySequence.getValue(line.lineSequence)
			line.lineSequence to (gross - couponBySequence.getOrDefault(line.lineSequence, Krw.ZERO))
		}
		val postCouponTotal = sum(postCouponBySequence.values)
		if (pointsToUse > postCouponTotal) {
			fail("Requested points exceed the amount remaining after coupon")
		}
		val pointsBySequence = allocate(
			total = pointsToUse,
			bases = lines.map { AllocationBase(it.lineSequence, postCouponBySequence.getValue(it.lineSequence)) },
		)

		val pricedLines = lines.map { line ->
			val gross = grossBySequence.getValue(line.lineSequence)
			val coupon = couponBySequence.getOrDefault(line.lineSequence, Krw.ZERO)
			val points = pointsBySequence.getOrDefault(line.lineSequence, Krw.ZERO)
			PricedLine(
				lineSequence = line.lineSequence,
				menuId = line.menuId,
				unitPrice = line.unitPrice,
				quantity = line.quantity,
				gross = gross,
				couponDiscount = coupon,
				pointsApplied = points,
				cashPayable = gross - coupon - points,
			)
		}
		val payable = subtotal - couponDiscount - pointsToUse

		check(sum(pricedLines.map(PricedLine::couponDiscount)) == couponDiscount)
		check(sum(pricedLines.map(PricedLine::pointsApplied)) == pointsToUse)
		check(sum(pricedLines.map(PricedLine::cashPayable)) == payable)

		return OrderPricing(
			lines = pricedLines,
			subtotal = subtotal,
			couponDiscount = couponDiscount,
			pointsApplied = pointsToUse,
			payable = payable,
		)
	}

	private fun validateLines(lines: List<PricingLine>) {
		if (lines.isEmpty()) {
			fail("At least one pricing line is required")
		}
		if (lines.map(PricingLine::lineSequence) != lines.indices.toList()) {
			fail("Line sequences must be contiguous and preserve request order")
		}
		if (lines.any { it.quantity < 1 }) {
			fail("Order line quantity must be positive")
		}
	}

	private fun allocate(total: Krw, bases: List<AllocationBase>): Map<Int, Krw> {
		if (total == Krw.ZERO) {
			return emptyMap()
		}
		val positiveBases = bases.filter { it.amount > Krw.ZERO }
		val basisTotal = sum(positiveBases.map(AllocationBase::amount))
		if (total > basisTotal || positiveBases.isEmpty()) {
			fail("Allocation total exceeds its basis")
		}

		val basisBigInteger = BigInteger.valueOf(basisTotal.value)
		val allocations = positiveBases.associate { base ->
			val floor = BigInteger.valueOf(total.value)
				.multiply(BigInteger.valueOf(base.amount.value))
				.divide(basisBigInteger)
				.longValueExact()
			base.lineSequence to floor
		}.toMutableMap()
		var remainder = total.value - allocations.values.sum()
		val remainderOrder = positiveBases.sortedWith(
			compareByDescending<AllocationBase> { it.amount.value }
				.thenBy(AllocationBase::lineSequence),
		)
		var index = 0
		while (remainder > 0) {
			val sequence = remainderOrder[index].lineSequence
			allocations[sequence] = allocations.getValue(sequence) + 1
			remainder--
			index++
		}
		return allocations.mapValues { Krw.of(it.value) }
	}

	private fun sum(amounts: Iterable<Krw>): Krw =
		amounts.fold(Krw.ZERO, Krw::plus)

	private fun fail(message: String): Nothing =
		throw DomainFailure(FailureCode.INVALID_REQUEST, message)

	private data class AllocationBase(
		val lineSequence: Int,
		val amount: Krw,
	)
}
