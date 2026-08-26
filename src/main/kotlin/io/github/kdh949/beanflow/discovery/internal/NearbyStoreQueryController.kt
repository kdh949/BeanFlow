package io.github.kdh949.beanflow.discovery.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.discovery.api.CustomerStoreDisplayView
import io.github.kdh949.beanflow.discovery.api.NearbyStorePage
import io.github.kdh949.beanflow.discovery.api.NearbyStoreQueryOperations
import io.github.kdh949.beanflow.discovery.api.NearbyStoreView
import io.github.kdh949.beanflow.discovery.api.NextPickupWindowView
import io.github.kdh949.beanflow.discovery.api.SearchNearbyStoresCommand
import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class NearbyStorePageInfoResponse(
    val nextCursor: String?,
)

internal data class NearbyStorePageResponse(
    val items: List<NearbyStoreItemResponse>,
    val page: NearbyStorePageInfoResponse,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class NearbyStoreItemResponse(
    val storeId: java.util.UUID,
    val name: String,
    val distanceMeters: Long,
    val orderingAvailable: Boolean,
    val pickupAvailable: Boolean,
    val nextPickupWindow: NextPickupWindowView?,
    val customerDisplay: CustomerStoreDisplayView,
    val image: StorefrontImageView?,
)

/**
 * Public nearby search.
 *
 * Every query parameter is bound as raw text and validated by Discovery, so a malformed value
 * never reaches a framework conversion error whose message would echo the customer coordinate
 * into the response body or the application log. The coordinate is not echoed in the response and
 * this read produces no audit record and no domain event.
 */
@RestController
@RequestMapping("/api/v1/stores")
internal class NearbyStoreQueryController(
    private val queries: NearbyStoreQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/nearby")
    fun nearby(
        @RequestParam(required = false) latitude: String?,
        @RequestParam(required = false) longitude: String?,
        @RequestParam(required = false) radiusMeters: String?,
        @RequestParam(required = false) pickupAvailable: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: String?,
    ): NearbyStorePageResponse =
        queries
            .search(
                SearchNearbyStoresCommand(
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radiusMeters,
                    pickupAvailable = pickupAvailable,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()

    private fun NearbyStorePage.toResponse() =
        NearbyStorePageResponse(items.map(NearbyStoreView::toResponse), NearbyStorePageInfoResponse(nextCursor))
}

private fun NearbyStoreView.toResponse() =
    NearbyStoreItemResponse(
        storeId,
        name,
        distanceMeters,
        orderingAvailable,
        pickupAvailable,
        nextPickupWindow,
        customerDisplay,
        image,
    )
