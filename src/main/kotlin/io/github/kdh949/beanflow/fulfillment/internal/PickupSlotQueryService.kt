package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupSlotQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How far ahead the public slot list reaches (BR-05, ADR-076). Bounding the read by time rather than
 * by a row limit keeps the answer complete inside the window: nothing is silently truncated.
 */
internal val PICKUP_SLOT_QUERY_HORIZON: Duration = Duration.ofDays(7)

@Service
internal class PickupSlotQueryService(
    private val repository: PickupSlotQueryRepository,
) : PickupSlotQueryOperations {
    @Transactional(readOnly = true)
    override fun listOpenSlots(
        storeId: UUID,
        now: Instant,
    ): List<PickupSlotView> =
        try {
            repository
                .findOpenSlots(storeId, now, now.plus(PICKUP_SLOT_QUERY_HORIZON))
                .onEach(::requireProjectable)
        } catch (failure: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Pickup slot availability is unavailable",
            ).also { it.initCause(failure) }
        }

    /**
     * A negative remaining capacity or an inverted window means the owner counters were corrupted.
     * The read fails explicitly instead of publishing a nonsensical slot.
     */
    private fun requireProjectable(slot: PickupSlotView) {
        if (slot.remainingCapacity < 0 || !slot.endsAt.isAfter(slot.startsAt)) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Pickup slot projection is invalid",
            )
        }
    }
}
