package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class PickupReservationService(
	private val slotRepository: PickupSlotJpaRepository,
	private val reservationRepository: PickupReservationJpaRepository,
	private val identifierSource: IdentifierSource,
	private val clock: Clock,
) : PickupReservationOperations {

	@Transactional(propagation = Propagation.MANDATORY)
	override fun reserve(command: ReservePickupCommand): UUID {
		if (command.sourceReference.isBlank()) {
			fail(FailureCode.INVALID_REQUEST, "Pickup reservation source reference is required")
		}
		val slot = slotRepository.findLockedById(command.pickupSlotId)
			?: fail(FailureCode.RESOURCE_NOT_FOUND, "Pickup slot was not found")
		if (slot.storeId != command.storeId) {
			fail(FailureCode.INVALID_REQUEST, "Pickup slot belongs to another store")
		}
		reservationRepository.findBySourceReference(command.sourceReference)?.let {
			if (it.orderId == command.orderId && it.slotId == command.pickupSlotId &&
				it.state == PickupReservationState.RESERVED
			) {
				return it.id
			}
			fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup source reference is already terminal or reused")
		}
		reservationRepository.findByOrderId(command.orderId)?.let {
			fail(FailureCode.ORDER_STATE_CONFLICT, "Order already has a pickup reservation")
		}
		if (slot.reservedCount + slot.confirmedCount >= slot.capacity) {
			fail(FailureCode.PICKUP_SLOT_FULL, "Pickup slot capacity is exhausted")
		}

		slot.reserveOne()
		val now = clock.instant()
		val reservation = PickupReservationEntity(
			id = identifierSource.next(),
			orderId = command.orderId,
			slotId = command.pickupSlotId,
			state = PickupReservationState.RESERVED,
			expiresAt = command.expiresAt,
			sourceReference = command.sourceReference,
			createdAt = now,
			updatedAt = now,
		)
		reservationRepository.save(reservation)
		return reservation.id
	}

	@Transactional(propagation = Propagation.MANDATORY)
	override fun confirm(orderId: UUID, sourceReference: String): ReservationTransitionResult {
		val current = reservationRepository.findByOrderId(orderId)
			?: return ReservationTransitionResult.NOT_ELIGIBLE
		val slot = slotRepository.findLockedById(current.slotId)
			?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved pickup slot is missing")
		val reservation = reservationRepository.findLockedByOrderId(orderId)
			?: return ReservationTransitionResult.NOT_ELIGIBLE
		if (reservation.sourceReference != sourceReference) {
			fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup confirmation source does not match")
		}
		return when (reservation.state) {
			PickupReservationState.RESERVED -> {
				slot.confirmOne()
				reservation.state = PickupReservationState.CONFIRMED
				reservation.updatedAt = clock.instant()
				ReservationTransitionResult.APPLIED
			}
			PickupReservationState.CONFIRMED -> ReservationTransitionResult.ALREADY_APPLIED
			PickupReservationState.EXPIRED -> ReservationTransitionResult.NOT_ELIGIBLE
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	override fun expire(orderId: UUID, now: Instant, sourceReference: String): ReservationTransitionResult {
		val current = reservationRepository.findByOrderId(orderId)
			?: return ReservationTransitionResult.NOT_ELIGIBLE
		val slot = slotRepository.findLockedById(current.slotId)
			?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved pickup slot is missing")
		val reservation = reservationRepository.findLockedByOrderId(orderId)
			?: return ReservationTransitionResult.NOT_ELIGIBLE
		if (reservation.sourceReference != sourceReference) {
			fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup expiry source does not match")
		}
		if (now.isBefore(reservation.expiresAt)) {
			return ReservationTransitionResult.NOT_ELIGIBLE
		}
		return when (reservation.state) {
			PickupReservationState.RESERVED -> {
				slot.releaseOne()
				reservation.state = PickupReservationState.EXPIRED
				reservation.updatedAt = now
				ReservationTransitionResult.APPLIED
			}
			PickupReservationState.EXPIRED -> ReservationTransitionResult.ALREADY_APPLIED
			PickupReservationState.CONFIRMED -> ReservationTransitionResult.NOT_ELIGIBLE
		}
	}

	private fun fail(code: FailureCode, message: String): Nothing = throw DomainFailure(code, message)
}
