package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupSlotQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupSlotView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

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
            repository.findOpenSlots(storeId, now).onEach(::requireProjectable)
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
