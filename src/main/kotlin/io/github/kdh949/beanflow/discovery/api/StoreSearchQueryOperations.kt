package io.github.kdh949.beanflow.discovery.api

import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import java.time.Instant
import java.util.UUID

/**
 * Unified store search over store, brand, region and available menu names (ADR-103 A1 to A7).
 *
 * Discovery owns request validation, cursor translation and the response projection. The search is
 * read-only: it writes no AuditRecord, publishes no domain event and stores the customer's query,
 * tokens and coordinate nowhere (구현 불변식 17).
 */
interface StoreSearchQueryOperations : DiscoveryApi {
    fun search(command: SearchStoresCommand): StoreSearchPage
}

/**
 * Raw public query input.
 *
 * Everything stays unparsed text until Discovery validates it, so a rejected request is answered by
 * a message that echoes neither the search text nor the coordinate.
 */
data class SearchStoresCommand(
    val query: String?,
    val sort: String?,
    val latitude: String?,
    val longitude: String?,
    val radiusMeters: String?,
    val openOnly: String?,
    val cursor: String?,
    val limit: String?,
    val now: Instant,
)

data class StoreSearchPage(
    val items: List<StoreSearchItemView>,
    val nextCursor: String?,
    /** True only when a coordinate pair was supplied, which is when [StoreSearchItemView.distanceMeters] is present. */
    val distanceAvailable: Boolean,
)

/**
 * One public search result. The relevance score itself is deliberately absent: the formula is not a
 * public contract and stays tunable (ADR-103 A4).
 */
data class StoreSearchItemView(
    val storeId: UUID,
    val name: String,
    val brandName: String?,
    /** 시도·시군구·읍면동·리를 위에서 아래로 이은 표시용 지역명. */
    val regionName: String?,
    val matchReason: Set<StoreSearchTermKind>,
    val distanceMeters: Long?,
    val open: Boolean,
    val pickupAvailable: Boolean,
    val matchedMenus: List<StoreSearchMenuView>,
)

data class StoreSearchMenuView(
    val menuId: UUID,
    val name: String,
)
