package io.github.kdh949.beanflow.ordering

import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class OrderPricingCalculatorTest {

	private val calculator = OrderPricingCalculator()

	@Test
	fun `coupon is allocated only to eligible lines and points use post-coupon balances`() {
		val result = calculator.calculate(
			lines = listOf(
				line(sequence = 0, unitPrice = 100, eligible = true),
				line(sequence = 1, unitPrice = 100, eligible = false),
			),
			couponDiscount = Krw.of(100),
			pointsToUse = Krw.of(50),
		)

		assertThat(result.lines.map { it.couponDiscount.value }).containsExactly(100, 0)
		assertThat(result.lines.map { it.pointsApplied.value }).containsExactly(0, 50)
		assertThat(result.lines.map { it.cashPayable.value }).containsExactly(0, 50)
		assertThat(result.payable.value).isEqualTo(50)
	}

	@Test
	fun `remainder follows larger basis then line sequence`() {
		val result = calculator.calculate(
			lines = listOf(
				line(sequence = 0, unitPrice = 100, eligible = true),
				line(sequence = 1, unitPrice = 100, eligible = true),
				line(sequence = 2, unitPrice = 200, eligible = true),
			),
			couponDiscount = Krw.of(3),
			pointsToUse = Krw.of(3),
		)

		assertThat(result.lines.map { it.couponDiscount.value }).containsExactly(1, 0, 2)
		assertThat(result.lines.sumOf { it.couponDiscount.value }).isEqualTo(3)
		assertThat(result.lines.sumOf { it.pointsApplied.value }).isEqualTo(3)
		assertThat(result.lines.sumOf { it.cashPayable.value }).isEqualTo(result.payable.value)
		result.lines.forEach {
			assertThat(it.couponDiscount.value + it.pointsApplied.value + it.cashPayable.value)
				.isEqualTo(it.gross.value)
		}
	}

	@Test
	fun `points exceeding post-coupon amount fail explicitly`() {
		assertThatThrownBy {
			calculator.calculate(
				lines = listOf(line(sequence = 0, unitPrice = 100, eligible = true)),
				couponDiscount = Krw.of(80),
				pointsToUse = Krw.of(21),
			)
		}
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
			}
	}

	@Test
	fun `money overflow fails instead of wrapping`() {
		assertThatThrownBy { Krw.of(Long.MAX_VALUE).multiply(2) }
			.isInstanceOfSatisfying(DomainFailure::class.java) {
				assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
			}
	}

	private fun line(sequence: Int, unitPrice: Long, eligible: Boolean): PricingLine =
		PricingLine(
			lineSequence = sequence,
			menuId = UUID.nameUUIDFromBytes("menu-$sequence".toByteArray()),
			unitPrice = Krw.of(unitPrice),
			quantity = 1,
			couponEligible = eligible,
		)
}
