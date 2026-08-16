package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.RefundableOrderLineSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class RefundAllocationCalculatorTest {
    private val firstLineId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val secondLineId = UUID.fromString("00000000-0000-4000-8000-000000000002")

    @Test
    fun `unit allocation spreads coupon by remainder and points by post-coupon balance`() {
        val line =
            line(
                orderLineId = firstLineId,
                lineSequence = 0,
                unitPriceKrw = 1_000,
                quantity = 3,
                couponDiscountKrw = 100,
                pointsAppliedKrw = 500,
            )

        val units = RefundAllocationCalculator.unitAllocations(line)

        assertThat(units.map { it.couponKrw }).containsExactly(34L, 33L, 33L)
        assertThat(units.sumOf { it.couponKrw }).isEqualTo(line.couponDiscountKrw)
        assertThat(units.sumOf { it.pointsKrw }).isEqualTo(line.pointsAppliedKrw)
        assertThat(units.sumOf { it.cashKrw }).isEqualTo(line.cashPayableKrw)
    }

    @Test
    fun `allocation is deterministic across repeated calls`() {
        val line =
            line(
                orderLineId = firstLineId,
                lineSequence = 0,
                unitPriceKrw = 3_333,
                quantity = 7,
                couponDiscountKrw = 1_111,
                pointsAppliedKrw = 999,
            )

        assertThat(RefundAllocationCalculator.unitAllocations(line))
            .isEqualTo(RefundAllocationCalculator.unitAllocations(line))
    }

    @Test
    fun `already refunded units are skipped and the next contiguous units are selected`() {
        val line =
            line(
                orderLineId = firstLineId,
                lineSequence = 0,
                unitPriceKrw = 1_000,
                quantity = 3,
                couponDiscountKrw = 100,
                pointsAppliedKrw = 500,
            )
        val units = RefundAllocationCalculator.unitAllocations(line)

        val allocated =
            RefundAllocationCalculator
                .allocate(listOf(line), mapOf(firstLineId to 1L), mapOf(firstLineId to 2L))
                .single()

        assertThat(allocated.firstUnitIndex).isEqualTo(1)
        assertThat(allocated.quantity).isEqualTo(2)
        assertThat(allocated.couponKrw).isEqualTo(units[1].couponKrw + units[2].couponKrw)
        assertThat(allocated.pointsKrw).isEqualTo(units[1].pointsKrw + units[2].pointsKrw)
        assertThat(allocated.cashKrw).isEqualTo(units[1].cashKrw + units[2].cashKrw)
    }

    @Test
    fun `a null selection allocates every remaining unit of every line`() {
        val lines = listOf(line(firstLineId, 0, quantity = 2), line(secondLineId, 1, quantity = 3))

        val allocated = RefundAllocationCalculator.allocate(lines, mapOf(secondLineId to 1L), null)

        assertThat(allocated.map { it.line.orderLineId to it.quantity })
            .containsExactly(firstLineId to 2L, secondLineId to 2L)
    }

    @Test
    fun `an unselected line is excluded instead of being refunded at zero`() {
        val lines = listOf(line(firstLineId, 0, quantity = 2), line(secondLineId, 1, quantity = 3))

        val allocated = RefundAllocationCalculator.allocate(lines, emptyMap(), mapOf(secondLineId to 1L))

        assertThat(allocated.map { it.line.orderLineId }).containsExactly(secondLineId)
    }

    @Test
    fun `a quantity over the remaining units is unavailable rather than silently reduced`() {
        val lines = listOf(line(firstLineId, 0, quantity = 3))

        assertThatThrownBy {
            RefundAllocationCalculator.allocate(lines, mapOf(firstLineId to 2L), mapOf(firstLineId to 2L))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.REFUND_QUANTITY_UNAVAILABLE)
        }
    }

    @Test
    fun `an OrderLine from another order is rejected`() {
        val lines = listOf(line(firstLineId, 0, quantity = 1))

        assertThatThrownBy {
            RefundAllocationCalculator.allocate(lines, emptyMap(), mapOf(secondLineId to 1L))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
    }

    @Test
    fun `consumption beyond the snapshot quantity is a dependency failure`() {
        val lines = listOf(line(firstLineId, 0, quantity = 1))

        assertThatThrownBy {
            RefundAllocationCalculator.allocate(lines, mapOf(firstLineId to 2L), null)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    @Test
    fun `an inconsistent gross snapshot is not allocated`() {
        val line = line(firstLineId, 0, quantity = 2).copy(grossKrw = 1)

        assertThatThrownBy {
            RefundAllocationCalculator.unitAllocations(line)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    private fun line(
        orderLineId: UUID,
        lineSequence: Int,
        unitPriceKrw: Long = 1_000,
        quantity: Long = 1,
        couponDiscountKrw: Long = 0,
        pointsAppliedKrw: Long = 0,
    ): RefundableOrderLineSnapshot {
        val gross = unitPriceKrw * quantity
        return RefundableOrderLineSnapshot(
            orderLineId = orderLineId,
            lineSequence = lineSequence,
            menuName = "아메리카노",
            unitPriceKrw = unitPriceKrw,
            quantity = quantity,
            grossKrw = gross,
            couponDiscountKrw = couponDiscountKrw,
            pointsAppliedKrw = pointsAppliedKrw,
            cashPayableKrw = gross - couponDiscountKrw - pointsAppliedKrw,
        )
    }
}
