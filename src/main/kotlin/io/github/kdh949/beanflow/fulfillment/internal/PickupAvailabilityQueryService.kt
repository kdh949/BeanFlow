package io.github.kdh949.beanflow.fulfillment.internal

import io.github.kdh949.beanflow.fulfillment.api.PickupAvailabilityQueryOperations
import io.github.kdh949.beanflow.fulfillment.api.PickupAvailabilityView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class PickupAvailabilityQueryService(
    private val repository: PickupAvailabilityQueryRepository,
) : PickupAvailabilityQueryOperations {
    @Transactional(readOnly = true)
    override fun findEarliestAvailableSlots(
        storeIds: Collection<UUID>,
        now: Instant,
    ): Map<UUID, PickupAvailabilityView> {
        // 후보가 없으면 물어볼 것이 없다. 빈 배열을 바인딩해 한 번 더 왕복하지 않는다.
        val candidates = storeIds.toSet()
        if (candidates.isEmpty()) return emptyMap()
        val rows =
            try {
                // 조회 창은 공개 슬롯 목록과 같은 7일이다. endpoint마다 다른 창을 쓰면
                // 목록에는 있는데 검색은 불가로 보이는 상태가 생긴다.
                repository.findAvailability(candidates, now, now.plus(PICKUP_SLOT_QUERY_HORIZON))
            } catch (failure: DataAccessException) {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Pickup availability is unavailable",
                ).also { it.initCause(failure) }
            }
        if (rows.any { it.corruptedCount > 0 }) {
            // 손상 counter를 "가용 아님"으로 접으면 정상적으로 닫힌 매장과 구분되지 않는다.
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Pickup slot projection is invalid",
            )
        }
        return rows
            .mapNotNull { row ->
                val startsAt = row.earliestStartsAt ?: return@mapNotNull null
                val endsAt = row.earliestEndsAt ?: return@mapNotNull null
                row.storeId to PickupAvailabilityView(row.storeId, startsAt, endsAt)
            }.toMap()
    }
}
