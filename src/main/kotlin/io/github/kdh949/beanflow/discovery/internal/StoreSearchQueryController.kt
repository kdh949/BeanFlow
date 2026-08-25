package io.github.kdh949.beanflow.discovery.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.discovery.api.SearchStoresCommand
import io.github.kdh949.beanflow.discovery.api.StoreSearchItemView
import io.github.kdh949.beanflow.discovery.api.StoreSearchMenuView
import io.github.kdh949.beanflow.discovery.api.StoreSearchPage
import io.github.kdh949.beanflow.discovery.api.StoreSearchQueryOperations
import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class StoreSearchPageInfoResponse(
    val nextCursor: String?,
)

/**
 * `distanceMeters` is omitted rather than sent as `null` when no coordinate was supplied, so a
 * client cannot mistake "no coordinate given" for "zero metres away".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class StoreSearchItemResponse(
    val storeId: UUID,
    val name: String,
    val brandName: String?,
    val regionName: String?,
    val matchReason: List<StoreSearchTermKind>,
    val distanceMeters: Long?,
    val open: Boolean,
    val pickupAvailable: Boolean,
    val matchedMenus: List<StoreSearchMenuView>,
    val image: StorefrontImageView?,
)

internal data class StoreSearchPageResponse(
    val items: List<StoreSearchItemResponse>,
    val page: StoreSearchPageInfoResponse,
    /**
     * Required by the `StoreSearchPage` schema. It tells a client whether `distanceMeters` is
     * absent because no store had a distance or because no coordinate was supplied at all.
     */
    val distanceAvailable: Boolean,
)

/**
 * Public unified store search over store, brand, region and available menu names (BR-47, ADR-103).
 *
 * Every query parameter is bound as raw text and validated by Discovery, so a malformed value never
 * reaches a framework conversion error whose message would echo the search text or the customer
 * coordinate into the response body, an application log or a trace. Neither the query nor the
 * coordinate is echoed in the response, and this read produces no audit record and no domain event.
 */
@RestController
@RequestMapping("/api/v1/stores")
internal class StoreSearchQueryController(
    private val queries: StoreSearchQueryOperations,
    private val clock: Clock,
) {
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) latitude: String?,
        @RequestParam(required = false) longitude: String?,
        @RequestParam(required = false) radiusMeters: String?,
        @RequestParam(required = false) pickupAvailable: String?,
        @RequestParam(required = false) openOnly: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: String?,
    ): StoreSearchPageResponse =
        queries
            .search(
                SearchStoresCommand(
                    query = query,
                    sort = sort,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radiusMeters,
                    pickupAvailable = pickupAvailable,
                    openOnly = openOnly,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()

    private fun StoreSearchPage.toResponse() =
        StoreSearchPageResponse(
            items = items.map(StoreSearchItemView::toResponse),
            page = StoreSearchPageInfoResponse(nextCursor),
            distanceAvailable = distanceAvailable,
        )
}

/** `matchReason` is published in the declared term-kind order so the array is deterministic. */
private fun StoreSearchItemView.toResponse() =
    StoreSearchItemResponse(
        storeId = storeId,
        name = name,
        brandName = brandName,
        regionName = regionName,
        matchReason = StoreSearchTermKind.entries.filter { it in matchReason },
        distanceMeters = distanceMeters,
        open = open,
        pickupAvailable = pickupAvailable,
        matchedMenus = matchedMenus,
        image = image,
    )
