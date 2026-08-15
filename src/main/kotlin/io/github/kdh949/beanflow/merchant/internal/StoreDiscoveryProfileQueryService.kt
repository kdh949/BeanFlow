package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileProjection
import io.github.kdh949.beanflow.merchant.api.NearbyStoreProfileQuery
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    }

    private companion object {
        const val MIN_RADIUS_METERS = 1
        const val MAX_RADIUS_METERS = 10_000
    }
}
