package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.ordering.internal.domain.Krw
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.ordering.internal.domain.OrderPricingCalculator
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.ordering.internal.domain.PricingLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class FastReorderPolicyTest {
    @Test
    fun `only completed cancelled rejected and expired source states are allowed`() {
        val allowed = setOf(OrderState.COMPLETED, OrderState.CANCELLED, OrderState.REJECTED, OrderState.EXPIRED)

        OrderState.entries.forEach { state ->
            assertThat(FastReorderSourcePolicy.isAllowed(state))
                .describedAs(state.name)
                .isEqualTo(state in allowed)
        }
    }

    @Test
    fun `price comparison contains changed lines only and uses current minus source`() {
        val menuId = UUID.randomUUID()
        val source = listOf(line(menuId, unitPriceKrw = 1_000, quantity = 2))
        val current = order(menuId, unitPriceKrw = 1_200, quantity = 2)

        val comparison = FastReorderPriceComparison.calculate(source, current)

        assertThat(comparison.hasPriceChanges).isTrue()
        assertThat(comparison.sourceSubtotalKrw).isEqualTo(2_000)
        assertThat(comparison.currentSubtotalKrw).isEqualTo(2_400)
        assertThat(comparison.subtotalDifferenceKrw).isEqualTo(400)
        val change = comparison.items.single()
        assertThat(change.sourceUnitPriceKrw).isEqualTo(1_000)
        assertThat(change.currentUnitPriceKrw).isEqualTo(1_200)
        assertThat(change.lineDifferenceKrw).isEqualTo(400)
    }

    @Test
    fun `unchanged price has zero difference and no item entries`() {
        val menuId = UUID.randomUUID()

        val comparison =
            FastReorderPriceComparison.calculate(
                listOf(line(menuId, unitPriceKrw = 1_000, quantity = 2)),
                order(menuId, unitPriceKrw = 1_000, quantity = 2),
            )

        assertThat(comparison.hasPriceChanges).isFalse()
        assertThat(comparison.subtotalDifferenceKrw).isZero()
        assertThat(comparison.items).isEmpty()
    }

    private fun line(
        menuId: UUID,
        unitPriceKrw: Long,
        quantity: Long,
    ): OrderLineEntity =
        OrderLineEntity(
            id = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            lineSequence = 0,
            menuId = menuId,
            menuName = "Source menu",
            optionNamesJson = "[]",
            optionSelectionSnapshotState = OptionSelectionSnapshotState.SNAPSHOTTED,
            normalizedOptionIds = emptyList(),
            sellableRequirementsJson = "[]",
            unitPriceKrw = unitPriceKrw,
            quantity = quantity,
            grossKrw = Math.multiplyExact(unitPriceKrw, quantity),
            couponDiscountKrw = 0,
            pointsAppliedKrw = 0,
            cashPayableKrw = Math.multiplyExact(unitPriceKrw, quantity),
        )

    private fun order(
        menuId: UUID,
        unitPriceKrw: Long,
        quantity: Long,
    ): Order {
        val quote =
            MenuLineQuote(
                menuId = menuId,
                menuName = "Current menu",
                optionSnapshots = emptyList(),
                unitPriceKrw = unitPriceKrw,
                quantity = quantity,
                sellableUnitRequirements = listOf(SellableUnitRequirement(UUID.randomUUID(), 1)),
            )
        val pricing =
            OrderPricingCalculator().calculate(
                lines = listOf(PricingLine(0, menuId, Krw.of(unitPriceKrw), quantity, false)),
                couponDiscount = Krw.ZERO,
                pointsToUse = Krw.ZERO,
            )
        val now = Instant.parse("2026-08-09T00:00:00Z")
        return Order.pendingPayment(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            pickupSlotId = UUID.randomUUID(),
            lineIds = listOf(UUID.randomUUID()),
            quotes = listOf(quote),
            pricing = pricing,
            createdAt = now,
            reservationExpiresAt = now.plusSeconds(300),
        )
    }
}
