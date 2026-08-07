package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.StoreCatalogQueryOperations
import io.github.kdh949.beanflow.discovery.api.StoreMenuItemView
import io.github.kdh949.beanflow.discovery.api.StorePickupSlotView
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
 * Both reads return current owner state and expose no write entity. A store with no menus or no
 * open slots is a legitimate empty list; a missing store is `404` and a persistence failure is
 * `503`.
 */
@RestController
@RequestMapping("/api/v1/stores")
internal class StoreCatalogController(
    private val queries: StoreCatalogQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/{storeId}/menus")
    fun menus(
        @PathVariable storeId: UUID,
    ): StoreMenuListResponse = StoreMenuListResponse(queries.listMenus(storeId))

    @GetMapping("/{storeId}/pickup-slots")
    fun pickupSlots(
        @PathVariable storeId: UUID,
    ): StorePickupSlotListResponse = StorePickupSlotListResponse(queries.listPickupSlots(storeId, clock.instant()))
}
