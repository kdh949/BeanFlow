package io.github.kdh949.beanflow.discovery.internal

/**
 * One page of candidates after an availability filter, plus the tuple its `nextCursor` must carry.
 *
 * [boundary] is the last candidate that was **examined**, not the last one returned. The two differ
 * whenever the filter rejects rows: a page can be short or empty while candidates remain.
 */
internal data class ScannedCandidatePage<T>(
    val items: List<T>,
    val boundary: T?,
)

/**
 * Applies a post-query availability filter to an ordered candidate list (ADR-103, ADR-070
 * 2026-08-15 nearby amendment).
 *
 * [fetched] is the ordered candidate list including the one extra probe row the caller asked for,
 * so `fetched.size > limit` means candidates remain beyond this page. Exactly the first [limit]
 * candidates are examined — never the probe — and the boundary is the last of them.
 *
 * Anchoring the cursor to the last *returned* row instead would skip every candidate the filter
 * rejected after it, because the next page starts strictly past that tuple. Anchoring to the probe
 * would skip the probe itself. Examining a fixed [limit] candidates per page also guarantees the
 * scan advances, so a long run of unavailable stores is paged through rather than looped over.
 *
 * `/stores/search` and `/stores/nearby` share this function so the two endpoints cannot drift into
 * different pagination semantics for the same filter.
 */
internal fun <T> scanCandidates(
    fetched: List<T>,
    limit: Int,
    keep: (T) -> Boolean,
): ScannedCandidatePage<T> {
    val examined = fetched.take(limit)
    return ScannedCandidatePage(
        items = examined.filter(keep),
        // examined.size == limit whenever the probe row is present, so `last()` is safe here.
        boundary = if (fetched.size > limit) examined.last() else null,
    )
}
