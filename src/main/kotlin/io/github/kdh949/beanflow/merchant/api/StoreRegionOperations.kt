package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

/**
 * One row of the 법정동 vocabulary (ADR-112 2절 and the 리 Amendment).
 *
 * The levels are separate fields rather than a single string because each one becomes its own
 * search term kind. [sigungu], [eupmyeondong] and [ri] are empty strings, never null: 세종특별자치시
 * has no 시군구 level at all, and a level that does not exist is not the same as a missing value.
 */
data class RegionSnapshot(
    val code: String,
    val sido: String,
    val sigungu: String,
    val eupmyeondong: String,
    val ri: String,
    val fullName: String,
)

data class StoreRegionAssignment(
    val storeId: UUID,
    val region: RegionSnapshot,
)

/**
 * Assigns a store to one 법정동 code.
 *
 * There is no clearing command. `region_code` becomes `NOT NULL` once every store has one, so a
 * store that lost its region would be one the coverage gate already ruled out.
 */
data class AssignStoreRegionCommand(
    val actorId: UUID,
    val idempotencyKey: String,
    val storeId: UUID,
    val regionCode: String,
    val now: Instant,
)

/**
 * The store region write port.
 *
 * Implementations declare [org.springframework.transaction.annotation.Propagation.MANDATORY]: the
 * region row, the store's `REGION_*` search terms, the replay ledger entry and the caller's
 * AuditRecord commit together or not at all.
 */
interface StoreRegionOperations {
    fun assignStoreRegion(command: AssignStoreRegionCommand): StoreRegionAssignment
}

data class RegionPage(
    val regions: List<RegionSnapshot>,
    val nextFullName: String?,
    val nextCode: String?,
)

/** Reads the 법정동 vocabulary so a store owner can pick a code instead of typing an address. */
interface RegionCatalogQueryOperations {
    fun find(code: String): RegionSnapshot?

    /**
     * Pages the vocabulary ordered by `(fullName ASC, code ASC)`.
     *
     * [query] is matched as a set of substrings that must all appear in the full name, so
     * `"서울 강남"` finds `서울특별시 강남구 역삼동` even though the two words are not adjacent.
     */
    fun search(
        query: String?,
        afterFullName: String?,
        afterCode: String?,
        limit: Int,
    ): RegionPage
}
