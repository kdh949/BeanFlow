package io.github.kdh949.beanflow.fulfillment.api

import io.github.kdh949.beanflow.shared.api.OrderTerminationTrigger
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

data class ReleasePickupAfterTerminationCommand(
    val orderId: UUID,
    val terminatedAt: Instant,
    val sourceReference: String,
    val trigger: OrderTerminationTrigger,
)

interface PickupReservationOperations {
    /**
     * Reserves one seat in the slot. Under the slot row lock the slot must still satisfy
     * `startsAt > now` (BR-05, ADR-076); a started or finished slot fails with
     * `ORDER_STATE_CONFLICT` and leaves every counter unchanged.
     *
     * Replaying the same `sourceReference` returns the existing reservation and is not re-validated
     * against the clock, so a reservation accepted in time stays retryable.
     */
    fun reserve(command: ReservePickupCommand): UUID

    fun confirm(
        orderId: UUID,
        sourceReference: String,
    ): ReservationTransitionReport

    fun release(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun expire(
        orderId: UUID,
        now: Instant,
        sourceReference: String,
    ): ReservationTransitionReport

    fun releaseConfirmedAfterTermination(command: ReleasePickupAfterTerminationCommand): ReservationTransitionReport
}
