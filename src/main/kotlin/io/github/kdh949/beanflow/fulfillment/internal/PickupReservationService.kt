package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupReservationGrant
import io.github.kdh949.beanflow.fulfillment.api.PickupReservationOperations
import io.github.kdh949.beanflow.fulfillment.api.ReleasePickupAfterTerminationCommand
import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReservationTransitionReport
import io.github.kdh949.beanflow.shared.api.ReservationTransitionResult
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
internal class PickupReservationService(
    private val slotRepository: PickupSlotJpaRepository,
    private val reservationRepository: PickupReservationJpaRepository,
    private val identifierSource: IdentifierSource,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : PickupReservationOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun reserve(command: ReservePickupCommand): PickupReservationGrant {
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
                return PickupReservationGrant(it.id, it.expiresAt)
            }
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup source reference is already terminal or reused")
        }
        reservationRepository.findByOrderId(command.orderId)?.let {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Order already has a pickup reservation")
        }
        // BR-05 pickup window: a slot may only be reserved while it has not started. The check runs
        // under the slot row lock and after the idempotent replay above, so a retry of a reservation
        // that was accepted in time still resolves to the stored reservation instead of failing.
        val now = clock.instant()
        if (!slot.startsAt.isAfter(now)) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup slot has already started")
        }
        // The deadline is minted at the precision the store can hold. `timestamptz` keeps
        // microseconds, so a finer value would come back rounded and a replay of the same
        // reservation would answer with a different grant than the first call did. Only the
        // requested lease can be finer — `slot.startsAt` was read from the store and is already
        // aligned — and truncating moves the deadline earlier by under a microsecond, so it never
        // extends a lease and never crosses the slot boundary.
        val expiresAt = minOf(command.expiresAt, slot.startsAt).truncatedTo(ChronoUnit.MICROS)
        if (!expiresAt.isAfter(now)) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup reservation lease has already expired")
        }
        if (slot.reservedCount + slot.confirmedCount >= slot.capacity) {
            fail(FailureCode.PICKUP_SLOT_FULL, "Pickup slot capacity is exhausted")
        }

        slot.reserveOne()
        val reservation =
            PickupReservationEntity(
                id = identifierSource.next(),
                orderId = command.orderId,
                slotId = command.pickupSlotId,
                state = PickupReservationState.RESERVED,
                expiresAt = expiresAt,
                sourceReference = command.sourceReference,
                createdAt = now,
                updatedAt = now,
            )
        reservationRepository.save(reservation)
        return PickupReservationGrant(reservation.id, reservation.expiresAt)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun confirm(
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
            fail(FailureCode.ORDER_STATE_CONFLICT, "Pickup confirmation source does not match")
        }
        // The reservation deadline normally equals the earlier of the Order lease and startsAt.
        // Check the slot as well so a legacy or manually repaired row with a later deadline can
        // never turn an already started slot into CONFIRMED.
        if (!now.isBefore(reservation.expiresAt) || !now.isBefore(slot.startsAt)) {
            return report(ReservationTransitionResult.NOT_ELIGIBLE, reservation.id)
        }
        val result =
            when (reservation.state) {
                PickupReservationState.RESERVED -> {
                    slot.confirmOne()
                    reservation.state = PickupReservationState.CONFIRMED
                    reservation.updatedAt = now
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
                ?: return report(ReservationTransitionResult.NOT_ELIGIBLE).also {
                    recordRestoration(command, ReservationTransitionResult.NOT_ELIGIBLE)
                }
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
                report(ReservationTransitionResult.ALREADY_APPLIED, reservation.id).also {
                    recordRestoration(command, ReservationTransitionResult.ALREADY_APPLIED)
                }
            } else {
                restorationConflict(command, "Pickup termination release metadata conflicts")
            }
        }
        if (reservation.state != PickupReservationState.CONFIRMED) {
            restorationConflict(command, "Pickup reservation is not confirmed for termination release")
        }
        slot.releaseConfirmedOne()
        reservation.state = PickupReservationState.RELEASED_AFTER_TERMINATION
        reservation.restorationSourceReference = command.sourceReference
        reservation.restorationTrigger = command.trigger
        reservation.updatedAt = command.terminatedAt
        return report(ReservationTransitionResult.APPLIED, reservation.id).also {
            recordRestoration(command, ReservationTransitionResult.APPLIED)
        }
    }

    private fun report(
        result: ReservationTransitionResult,
        vararg ids: UUID,
    ) = ReservationTransitionReport(result, ids.toList())

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private fun recordRestoration(
        command: ReleasePickupAfterTerminationCommand,
        outcome: ReservationTransitionResult,
    ) {
        afterCommit {
            meterRegistry
                .counter(
                    "beanflow.resource.restoration.count",
                    "owner",
                    "pickup",
                    "trigger",
                    command.trigger.name.lowercase(),
                    "outcome",
                    outcome.name.lowercase(),
                ).increment()
            if (outcome == ReservationTransitionResult.APPLIED) {
                meterRegistry
                    .summary(
                        "beanflow.resource.restoration.lag",
                        "owner",
                        "pickup",
                        "trigger",
                        command.trigger.name.lowercase(),
                    ).record(
                        Duration
                            .between(command.terminatedAt, clock.instant())
                            .seconds
                            .coerceAtLeast(0)
                            .toDouble(),
                    )
            }
        }
    }

    private fun restorationConflict(
        command: ReleasePickupAfterTerminationCommand,
        message: String,
    ): Nothing {
        meterRegistry
            .counter(
                "beanflow.resource.restoration.source_conflict.count",
                "owner",
                "pickup",
                "trigger",
                command.trigger.name.lowercase(),
            ).increment()
        fail(FailureCode.COMPENSATION_SOURCE_CONFLICT, message)
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}
