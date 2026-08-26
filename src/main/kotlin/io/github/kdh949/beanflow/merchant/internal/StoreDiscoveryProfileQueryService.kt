package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileQuery
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreDiscoveryProfileQueryService(
    private val repository: StoreDiscoveryProfileQueryRepository,
) : StoreDiscoveryQueryOperations {
    @Transactional(readOnly = true)
    override fun findPickupCapableStoresNear(query: NearbyStoreProfileQuery): List<NearbyStoreProfileProjection> {
        require(query.radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            "Nearby store query radius must be validated before the spatial query"
        }
        require(query.limit >= 1) { "Nearby store query limit must be validated before the spatial query" }
        return repository.findPickupCapableStoresNear(query).onEach(::requireProjectable)
    }

    @Transactional(readOnly = true)
    override fun countIndexableStores(): Long = repository.countStores()

    @Transactional(readOnly = true)
    override fun findVisibleStores(storeIds: Collection<UUID>): List<StoreDiscoveryDisplayProjection> =
        repository.findVisibleStores(storeIds).onEach(::requireDisplayProjectable)

    /**
     * A blank owner name or a negative distance means the verified profile invariant was broken
     * after startup. The read fails explicitly instead of returning a placeholder store.
     */
    private fun requireProjectable(projection: NearbyStoreProfileProjection) {
        if (projection.name.isBlank() || projection.distanceMicrometers < 0) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store discovery profile projection is invalid",
            )
        }
        requireCustomerDisplayProjectable(projection.customerDisplay)
    }

    private fun requireDisplayProjectable(projection: StoreDiscoveryDisplayProjection) {
        if (projection.name.isBlank()) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store discovery display projection is invalid",
            )
        }
        requireCustomerDisplayProjectable(projection.customerDisplay)
    }

    private fun requireCustomerDisplayProjectable(display: StoreCustomerDisplayProjection) {
        val days = display.operatingHours?.days ?: return
        val completeWeek =
            days.size == java.time.DayOfWeek.entries.size && days.map { it.dayOfWeek }.toSet() ==
                java.time.DayOfWeek.entries
                    .toSet()
        val validTuples =
            days.all { day ->
                if (day.closed) {
                    day.opensAt == null && day.closesAt == null
                } else {
                    day.opensAt != null && day.closesAt != null && day.opensAt < day.closesAt
                }
            }
        if (!completeWeek || !validTuples) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store operating-hours projection is invalid",
            )
        }
    }

    private companion object {
        const val MIN_RADIUS_METERS = 1
        const val MAX_RADIUS_METERS = 10_000
    }
}
