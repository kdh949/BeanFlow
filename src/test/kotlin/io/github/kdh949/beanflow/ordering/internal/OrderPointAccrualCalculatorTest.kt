package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class OrderPointAccrualCalculatorTest {
    private val calculator = OrderPointAccrualCalculator()

    @Test
    fun `floor and half up are applied once to gross accrual`() {
        val line = line(sequence = 0, quantity = 1, unitPriceKrw = 1, cashPayableKrw = 1)

        val floor = calculator.calculate(policy(rateBps = 5_000, rounding = PointAccrualRoundingMode.FLOOR), listOf(line))
        val halfUp = calculator.calculate(policy(rateBps = 5_000, rounding = PointAccrualRoundingMode.HALF_UP), listOf(line))

        assertThat(floor.grossAccrualAmountKrw).isZero()
        assertThat(halfUp.grossAccrualAmountKrw).isEqualTo(1)
        assertThat(halfUp.units.single().accruedAmountKrw).isEqualTo(1)
    }

    @Test
    fun `gross calculation does not overflow at the largest supported money input`() {
        val result =
            calculator.calculate(
                policy(rateBps = 10_000),
                listOf(line(sequence = 0, quantity = 1, unitPriceKrw = Long.MAX_VALUE, cashPayableKrw = Long.MAX_VALUE)),
            )

        assertThat(result.grossAccrualAmountKrw).isEqualTo(Long.MAX_VALUE)
        assertThat(result.units.single().accruedAmountKrw).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `unit remainder follows cash descending then line sequence and unit position`() {
        val result =
            calculator.calculate(
                policy(rateBps = 5_000),
                listOf(
                    line(sequence = 0, quantity = 1, unitPriceKrw = 5, cashPayableKrw = 5),
                    line(sequence = 1, quantity = 2, unitPriceKrw = 3, cashPayableKrw = 6),
                ),
            )

        assertThat(result.grossAccrualAmountKrw).isEqualTo(5)
        assertThat(result.units.map { it.cashPayableKrw }).containsExactly(5, 3, 3)
        assertThat(result.units.map { it.accruedAmountKrw }).containsExactly(3, 1, 1)
        assertThat(result.units.sumOf { it.accruedAmountKrw }).isEqualTo(result.grossAccrualAmountKrw)
    }

    @Test
    fun `conceptual unit cash allocation matches coupon then point allocation`() {
        val result =
            calculator.calculate(
                policy(rateBps = 10_000),
                listOf(
                    OrderPointAccrualLineInput(
                        orderLineId = UUID.randomUUID(),
                        lineSequence = 0,
                        unitPriceKrw = 5,
                        quantity = 2,
                        grossKrw = 10,
                        couponDiscountKrw = 3,
                        pointsAppliedKrw = 3,
                        cashPayableKrw = 4,
                    ),
                ),
            )

        assertThat(result.units.map { it.cashPayableKrw }).containsExactly(2, 2)
        assertThat(result.units.map { it.accruedAmountKrw }).containsExactly(2, 2)
    }

    @Test
    fun `zero bps preserves units with zero accrual`() {
        val result =
            calculator.calculate(
                policy(rateBps = 0),
                listOf(line(sequence = 0, quantity = 2, unitPriceKrw = 100, cashPayableKrw = 200)),
            )

        assertThat(result.grossAccrualAmountKrw).isZero()
        assertThat(result.units).hasSize(2)
        assertThat(result.units).allMatch { it.accruedAmountKrw == 0L }
    }

    @Test
    fun `exact and Seoul calendar expiry use the approved completion boundary`() {
        val completedAt = Instant.parse("2026-08-01T15:30:00Z")

        assertThat(calculator.expiresAt(policy(validityDays = 2), completedAt))
            .isEqualTo(Instant.parse("2026-08-03T15:30:00Z"))
        assertThat(
            calculator.expiresAt(
                policy(
                    expiryRule = OrdinaryPointAccrualExpiryRule.SEOUL_CALENDAR_DAYS_FROM_COMPLETION,
                    validityDays = 2,
                ),
                completedAt,
            ),
        ).isEqualTo(Instant.parse("2026-08-03T15:00:00Z"))
    }

    @Test
    fun `inconsistent line snapshot fails explicitly`() {
        assertThatThrownBy {
            calculator.calculate(
                policy(),
                listOf(
                    OrderPointAccrualLineInput(
                        orderLineId = UUID.randomUUID(),
                        lineSequence = 0,
                        unitPriceKrw = 100,
                        quantity = 2,
                        grossKrw = 200,
                        couponDiscountKrw = 0,
                        pointsAppliedKrw = 0,
                        cashPayableKrw = 199,
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun policy(
        rateBps: Int = 100,
        rounding: PointAccrualRoundingMode = PointAccrualRoundingMode.FLOOR,
        expiryRule: OrdinaryPointAccrualExpiryRule = OrdinaryPointAccrualExpiryRule.EXACT_DURATION_FROM_COMPLETION,
        validityDays: Int = 365,
    ): OrdinaryPointAccrualPolicySnapshot =
        OrdinaryPointAccrualPolicySnapshot(
            policyVersionId = 1,
            scopeType = OrdinaryPointAccrualPolicyScopeType.GLOBAL,
            scopeReference = OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            accrualRateBps = rateBps,
            roundingMode = rounding,
            issuerType = PointAccrualIssuerType.PLATFORM,
            issuerReference = "beanflow-platform",
            expiryRule = expiryRule,
            validityDays = validityDays,
            canonicalPolicyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

    private fun line(
        sequence: Int,
        quantity: Long,
        unitPriceKrw: Long,
        cashPayableKrw: Long,
    ): OrderPointAccrualLineInput =
        OrderPointAccrualLineInput(
            orderLineId = UUID.nameUUIDFromBytes("line-$sequence".toByteArray()),
            lineSequence = sequence,
            unitPriceKrw = unitPriceKrw,
            quantity = quantity,
            grossKrw = Math.multiplyExact(unitPriceKrw, quantity),
            couponDiscountKrw = 0,
            pointsAppliedKrw = Math.subtractExact(Math.multiplyExact(unitPriceKrw, quantity), cashPayableKrw),
            cashPayableKrw = cashPayableKrw,
        )
}
