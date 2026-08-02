package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReleasePickupAfterTerminationCommand
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
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
        val slot =
            slotRepository.findLockedById(command.pickupSlotId)
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
        val reservation =
            PickupReservationEntity(
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
    override fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val slot =
            slotRepository.findLockedById(current.slotId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved pickup slot is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.sourceReference != sourceReference) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup confirmation source does not match")
        }
        val result =
            when (reservation.state) {
                PickupReservationState.RESERVED -> {
                    slot.confirmOne()
                    reservation.state = PickupReservationState.CONFIRMED
                    reservation.updatedAt = clock.instant()
                    ReservationTransitionResult.APPLIED
                }

                PickupReservationState.CONFIRMED -> {
                    ReservationTransitionResult.ALREADY_APPLIED
                }

                PickupReservationState.EXPIRED, PickupReservationState.RELEASED -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }

                PickupReservationState.RELEASED_AFTER_TERMINATION -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }
            }
        return report(result, reservation.id)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val slot =
            slotRepository.findLockedById(current.slotId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved pickup slot is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.sourceReference != sourceReference) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup release source does not match")
        }
        val result =
            when (reservation.state) {
                PickupReservationState.RESERVED -> {
                    slot.releaseOne()
                    reservation.state = PickupReservationState.RELEASED
                    reservation.updatedAt = now
                    ReservationTransitionResult.APPLIED
                }

                PickupReservationState.RELEASED -> {
                    ReservationTransitionResult.ALREADY_APPLIED
                }

                PickupReservationState.CONFIRMED, PickupReservationState.EXPIRED -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }

                PickupReservationState.RELEASED_AFTER_TERMINATION -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }
            }
        return report(result, reservation.id)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun expire(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val slot =
            slotRepository.findLockedById(current.slotId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Reserved pickup slot is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.sourceReference != sourceReference) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup expiry source does not match")
        }
        if (now.isBefore(reservation.expiresAt)) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }
        val result =
            when (reservation.state) {
                PickupReservationState.RESERVED -> {
                    slot.releaseOne()
                    reservation.state = PickupReservationState.EXPIRED
                    reservation.updatedAt = now
                    ReservationTransitionResult.APPLIED
                }

                PickupReservationState.EXPIRED -> {
                    ReservationTransitionResult.ALREADY_APPLIED
                }

                PickupReservationState.CONFIRMED, PickupReservationState.RELEASED -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }

                PickupReservationState.RELEASED_AFTER_TERMINATION -> {
                    ReservationTransitionResult.NOT_ELIGIBLE
                }
            }
        return report(result, reservation.id)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun releaseConfirmedAfterTermination(command: ReleasePickupAfterTerminationCommand): ReservationTransitionReport {
        val current =
            reservationRepository.findByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        val slot =
            slotRepository.findLockedById(current.slotId)
                ?: fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Confirmed pickup slot is missing")
        val reservation =
            reservationRepository.findLockedByOrderId(command.orderId)
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE)
        if (reservation.state == PickupReservationState.RELEASED_AFTER_TERMINATION) {
            return if (reservation.restorationSourceReference == command.sourceReference &&
                reservation.restorationTrigger == command.trigger
            ) {
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id)
            } else {
                fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Pickup termination release metadata conflicts")
            }
        }
        if (reservation.state != PickupReservationState.CONFIRMED) {
            fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, "Pickup reservation is not confirmed for termination release")
        }
        slot.releaseConfirmedOne()
        reservation.state = PickupReservationState.RELEASED_AFTER_TERMINATION
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = command.trigger
        reservation.updatedAt = command.terminatedAt
        return report(ReservationTransitionResult.APPLIED, reservation.id)
    }

    private fun report(
        result: ReservationTransitionResult,
        vararg ids: UUID,
    ) = ReservationTransitionReport(result, ids.toList())

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}
