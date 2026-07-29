package io.github.kdh949.beanflow.fulfillment.api

import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import java.time.Instant
import java.util.UUID

data class ReservePickupCommand(
	val orderId: UUID,
	val storeId: UUID,
	val pickupSlotId: UUID,
	val expiresAt: Instant,
	val sourceReference: String,
)

interface PickupReservationOperations {
	fun reserve(command: ReservePickupCommand): UUID
	fun confirm(orderId: UUID, sourceReference: String): ReservationTransitionReport
	fun expire(orderId: UUID, now: Instant, sourceReference: String): ReservationTransitionReport
}
