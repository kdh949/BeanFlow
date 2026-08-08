package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.ReservePickupCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The `startsAt > now` boundary itself (BR-05, ADR-076). A system clock never lands exactly on a
 * slot's start instant, so the equality case is pinned here with a fixed clock; the surrounding
 * behaviour is covered against PostgreSQL in [PickupReservationRepositoryTest].
 */
internal class PickupSlotReservationWindowUnitTest {
    private val slots = mock<PickupSlotJpaRepository>()
    private val reservations = mock<PickupReservationJpaRepository>()

    @Test
    fun `a slot starting exactly now is rejected and nothing is written`() {
        val slot = slotStartingAt(NOW)
        val service = serviceFor(slot)

        val failure = runCatching { service.reserve(command()) }.exceptionOrNull()

        assertThat(failure).isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ORDER_STATE_CONFLICT)
        }
        assertThat(slot.reservedCount).isZero()
        assertThat(slot.confirmedCount).isZero()
        // The two lookups above are the only reservation-repository calls; no save happened.
        verify(reservations).findBySourceReference(SOURCE)
        verify(reservations).findByOrderId(ORDER_ID)
        verifyNoMoreInteractions(reservations)
    }

    @Test
    fun `a slot starting one nanosecond later is accepted`() {
        val slot = slotStartingAt(NOW.plusNanos(1))
        val service = serviceFor(slot)

        assertThat(service.reserve(command()).reservationId).isEqualTo(RESERVATION_ID)
        assertThat(slot.reservedCount).isEqualTo(1)
    }

    private fun serviceFor(slot: PickupSlotEntity): PickupReservationService {
        `when`(slots.findLockedById(SLOT_ID)).thenReturn(slot)
        `when`(reservations.findBySourceReference(SOURCE)).thenReturn(null)
        `when`(reservations.findByOrderId(ORDER_ID)).thenReturn(null)
        return PickupReservationService(
            slotRepository = slots,
            reservationRepository = reservations,
            identifierSource = IdentifierSource { RESERVATION_ID },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    private fun slotStartingAt(startsAt: Instant) =
        PickupSlotEntity(
            id = SLOT_ID,
            storeId = STORE_ID,
            startsAt = startsAt,
            endsAt = startsAt.plusSeconds(600),
            capacity = 2,
        )

    private fun command() =
        ReservePickupCommand(
            orderId = ORDER_ID,
            storeId = STORE_ID,
            pickupSlotId = SLOT_ID,
            expiresAt = NOW.plusSeconds(300),
            sourceReference = SOURCE,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val STORE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val SLOT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val ORDER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val RESERVATION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")
        const val SOURCE = "pickup-order-window-boundary"
    }
}
