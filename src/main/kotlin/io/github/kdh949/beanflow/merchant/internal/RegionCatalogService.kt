package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.RegionCatalogQueryOperations
import io.github.kdh949.beanflow.merchant.api.RegionSnapshot
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat

internal data class RegionCatalogPage(
    val regions: List<RegionSnapshot>,
    val nextCursor: String?,
)

internal data class RegionSort(
    val fullName: String,
    val code: String,
)

/**
 * The 법정동 picker behind `GET /regions`.
 *
 * The cursor lives here rather than in the controller so that the controller has no bean the web
 * layer alone cannot supply, which is the same split the operator brand list uses.
 *
 * The vocabulary is public reference data: no actor filter narrows it, and nothing about a store
 * is exposed by reading it.
 */
@Service
internal class RegionCatalogService(
    private val regions: RegionCatalogQueryOperations,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        query: String?,
        cursor: String?,
        limit: Int?,
    ): RegionCatalogPage {
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        if (pageSize !in 1..MAX_PAGE_SIZE) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "limit must be between 1 and $MAX_PAGE_SIZE")
        }
        val scope = cursorScope(query)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val page = regions.search(query, after?.fullName, after?.code, pageSize)
        val nextFullName = page.nextFullName
        val nextCode = page.nextCode
        val nextCursor =
            if (nextFullName != null && nextCode != null) {
                cursors.issue(scope, RegionSort(nextFullName, nextCode), clock.instant().plus(CURSOR_TTL))
            } else {
                null
            }
        return RegionCatalogPage(page.regions, nextCursor)
    }

    /**
     * Binds the cursor to this endpoint and to the query it was issued for.
     *
     * Continuing a `"강남"` page with a `"부산"` cursor would page through a different result set
     * from an unrelated position, so the query is part of the filter digest.
     */
    private fun cursorScope(query: String?): SignedCursorScope<RegionSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("$CURSOR_ENDPOINT\n${query?.trim().orEmpty()}"),
            sortAdapter =
                object : CursorSortAdapter<RegionSort> {
                    override fun encode(sort: RegionSort): List<String> = listOf(sort.fullName, sort.code)

                    override fun decode(values: List<String>): RegionSort? {
                        if (values.size != 2) return null
                        return RegionSort(values[0], values[1])
                    }
                },
        )

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        const val CURSOR_ENDPOINT = "regions"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        val CURSOR_TTL: Duration = Duration.ofMinutes(30)
    }
}
