package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StoreCatalogQueryOperations
import io.github.kdh949.beanflow.discovery.api.StoreMenuItemView
import io.github.kdh949.beanflow.discovery.api.StorePickupSlotView
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal data class StoreMenuListResponse(
    val items: List<StoreMenuItemView>,
)

internal data class StorePickupSlotListResponse(
    val items: List<StorePickupSlotView>,
)

/**
 * Public store catalogue endpoints.
 *
 * Every read returns current owner state and exposes no write entity. A store with no menus or no
 * open slots is a legitimate empty list; a missing store is `404` and a persistence failure is
 * `503`.
 */
@RestController
@RequestMapping("/api/v1/stores")
internal class StoreCatalogController(
    private val queries: StoreCatalogQueryOperations,
    private val hydrator: CustomerStoreHydrator,
    private val clock: Clock,
) {
    /**
     * The display identity of one store, so that a customer who opens the store screen by URL or
     * reloads it sees the store's real name instead of a placeholder the client made up.
     *
     * A store the customer must not see is indistinguishable from one that does not exist, so both
     * are `404`.
     */
    @GetMapping("/{storeId}")
    fun store(
        @PathVariable storeId: UUID,
    ): CustomerStoreResponse =
        hydrator.hydrate(listOf(storeId), clock.instant()).firstOrNull()?.toResponse()
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store is not available")

    @GetMapping("/{storeId}/menus")
    fun menus(
        @PathVariable storeId: UUID,
    ): StoreMenuListResponse = StoreMenuListResponse(queries.listMenus(storeId))

    @GetMapping("/{storeId}/pickup-slots")
    fun pickupSlots(
        @PathVariable storeId: UUID,
    ): StorePickupSlotListResponse = StorePickupSlotListResponse(queries.listPickupSlots(storeId, clock.instant()))
}
