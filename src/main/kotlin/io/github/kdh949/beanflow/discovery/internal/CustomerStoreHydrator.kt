package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.fulfillment.api.PickupAvailabilityQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryDisplayProjection
import io.github.kdh949.beanflow.merchant.api.StoreDiscoveryQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Hydrates customer-owned store references through Merchant and Fulfillment without creating a
 * Discovery replica. References that no longer have a public Merchant profile are omitted; their
 * source rows or Order snapshots are never changed as a read side effect.
 */
@Component
internal class CustomerStoreHydrator(
    private val stores: StoreDiscoveryQueryOperations,
    private val availability: PickupAvailabilityQueryOperations,
    private val imageViews: StorefrontImageViewResolver,
) {
    @Transactional(readOnly = true)
    fun hydrate(
        storeIds: Collection<UUID>,
        now: Instant,
    ): List<CustomerStoreView> {
        val orderedIds = storeIds.distinct()
        val displays = visibleStores(orderedIds)
        val availableStoreIds = availability.findStoresWithAvailableSlots(displays.map(StoreDiscoveryDisplayProjection::storeId), now)
        val displayByStoreId = displays.associateBy(StoreDiscoveryDisplayProjection::storeId)
        return orderedIds.mapNotNull { storeId ->
            displayByStoreId[storeId]?.let { display ->
                CustomerStoreView(
                    storeId = display.storeId,
                    name = display.name,
                    pickupAvailable = display.pickupCapable && display.storeId in availableStoreIds,
                    image = imageViews.resolve(display.imageThumbnailKey),
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun isVisible(storeId: UUID): Boolean = visibleStores(listOf(storeId)).any { it.storeId == storeId }

    private fun visibleStores(storeIds: Collection<UUID>): List<StoreDiscoveryDisplayProjection> =
        try {
            stores.findVisibleStores(storeIds)
        } catch (failure: DataAccessException) {
            unavailable(failure)
        } catch (failure: TransactionException) {
            unavailable(failure)
        }

    private fun unavailable(cause: Throwable): Nothing =
        throw DomainFailure(
            FailureCode.DEPENDENCY_UNAVAILABLE,
            "Customer store display hydration is unavailable",
        ).also { it.initCause(cause) }
}
