package io.github.kdh949.beanflow.ordering.internal.domain

import io.github.kdh949.beanflow.merchant.api.MenuLineQuote
import io.github.kdh949.beanflow.merchant.api.OptionSnapshot
import io.github.kdh949.beanflow.merchant.api.SellableUnitRequirement
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class OrderState {
	PENDING_PAYMENT,
	PAID,
	EXPIRED,
	CANCELLED,
}

data class OrderLineSnapshot(
	val id: UUID,
	val lineSequence: Int,
	val menuId: UUID,
	val menuName: String,
	val options: List<OptionSnapshot>,
	val sellableUnitRequirements: List<SellableUnitRequirement>,
	val unitPriceKrw: Long,
	val quantity: Long,
	val grossKrw: Long,
	val couponDiscountKrw: Long,
	val pointsAppliedKrw: Long,
	val cashPayableKrw: Long,
)

class Order private constructor(
	val id: UUID,
	val customerId: UUID,
	val storeId: UUID,
	val pickupSlotId: UUID,
	val state: OrderState,
	val lines: List<OrderLineSnapshot>,
	val subtotalKrw: Long,
	val couponDiscountKrw: Long,
	val pointsAppliedKrw: Long,
	val payableKrw: Long,
	val createdAt: Instant,
	val reservationExpiresAt: Instant?,
) {

	companion object {
		private val RESERVATION_LEASE: Duration = Duration.ofMinutes(5)

		fun pendingPayment(
			id: UUID,
			customerId: UUID,
			storeId: UUID,
			pickupSlotId: UUID,
			lineIds: List<UUID>,
			quotes: List<MenuLineQuote>,
			pricing: OrderPricing,
			createdAt: Instant,
		): Order {
			if (quotes.size != pricing.lines.size || lineIds.size != quotes.size) {
				invalid("Quote, pricing and line identifiers must have the same size")
			}
			if (pricing.payable == Krw.ZERO) {
				invalid("A pending-payment order must have a positive payable amount")
			}
			val snapshots = snapshots(lineIds, quotes, pricing)
			return Order(
				id = id,
				customerId = customerId,
				storeId = storeId,
				pickupSlotId = pickupSlotId,
				state = OrderState.PENDING_PAYMENT,
				lines = snapshots,
				subtotalKrw = pricing.subtotal.value,
				couponDiscountKrw = pricing.couponDiscount.value,
				pointsAppliedKrw = pricing.pointsApplied.value,
				payableKrw = pricing.payable.value,
				createdAt = createdAt,
				reservationExpiresAt = createdAt.plus(RESERVATION_LEASE),
			)
		}

		fun benefitOnlyPaid(
			id: UUID,
			customerId: UUID,
			storeId: UUID,
			pickupSlotId: UUID,
			lineIds: List<UUID>,
			quotes: List<MenuLineQuote>,
			pricing: OrderPricing,
			createdAt: Instant,
		): Order {
			if (quotes.size != pricing.lines.size || lineIds.size != quotes.size) {
				invalid("Quote, pricing and line identifiers must have the same size")
			}
			if (pricing.payable != Krw.ZERO || pricing.pointsApplied == Krw.ZERO) {
				invalid("A BENEFIT_ONLY order requires zero payable and positive applied points")
			}
			val snapshots = snapshots(lineIds, quotes, pricing)
			return Order(
				id = id,
				customerId = customerId,
				storeId = storeId,
				pickupSlotId = pickupSlotId,
				state = OrderState.PAID,
				lines = snapshots,
				subtotalKrw = pricing.subtotal.value,
				couponDiscountKrw = pricing.couponDiscount.value,
				pointsAppliedKrw = pricing.pointsApplied.value,
				payableKrw = pricing.payable.value,
				createdAt = createdAt,
				reservationExpiresAt = null,
			)
		}

		private fun snapshots(
			lineIds: List<UUID>,
			quotes: List<MenuLineQuote>,
			pricing: OrderPricing,
		): List<OrderLineSnapshot> =
			quotes.indices.map { index ->
				val quote = quotes[index]
				val priced = pricing.lines[index]
				if (priced.lineSequence != index || quote.menuId != priced.menuId) {
					invalid("Quote and pricing line order must match")
				}
				OrderLineSnapshot(
					id = lineIds[index],
					lineSequence = index,
					menuId = quote.menuId,
					menuName = quote.menuName,
					options = quote.optionSnapshots.toList(),
					sellableUnitRequirements = quote.sellableUnitRequirements.toList(),
					unitPriceKrw = quote.unitPriceKrw,
					quantity = quote.quantity,
					grossKrw = priced.gross.value,
					couponDiscountKrw = priced.couponDiscount.value,
					pointsAppliedKrw = priced.pointsApplied.value,
					cashPayableKrw = priced.cashPayable.value,
				)
			}

		private fun invalid(message: String): Nothing =
			throw DomainFailure(FailureCode.INVALID_REQUEST, message)
	}
}
