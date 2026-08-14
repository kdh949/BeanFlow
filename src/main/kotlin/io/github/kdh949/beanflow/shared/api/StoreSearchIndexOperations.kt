package io.github.kdh949.beanflow.shared.api

import java.util.UUID

/**
 * The closed term vocabulary of the store search index (ADR-103 A1 and A7).
 *
 * The relevance weight of each kind is a Discovery ranking rule and deliberately does not live
 * here: `merchant` writes terms without knowing how they are scored.
 */
enum class StoreSearchTermKind {
    STORE_NAME,
    BRAND_NAME,
    REGION_SIDO,
    REGION_SIGUNGU,
    REGION_EUPMYEONDONG,
    REGION_RI,
    MENU_NAME,
}

/**
 * One searchable public attribute of a store, in its original display form.
 *
 * The caller passes raw text. Normalization is the index owner's job so that the write path and
 * the query path cannot drift apart (MD-2026-015).
 */
data class StoreSearchTermEntry(
    val kind: StoreSearchTermKind,
    val displayText: String,
    /** The menu row a `MENU_NAME` term came from. Every other kind is store-scoped. */
    val sourceId: UUID? = null,
) {
    init {
        require((kind == StoreSearchTermKind.MENU_NAME) == (sourceId != null)) {
            "Only MENU_NAME terms carry a source id"
        }
    }
}

/**
 * Replaces every term of the named kinds for one store.
 *
 * The kinds are stated separately from the entries so that "this store now has no brand" and
 * "this store has a brand" are the same operation. Passing entries alone could never express a
 * removal.
 */
data class ReplaceStoreSearchTermsCommand(
    val storeId: UUID,
    val kinds: Set<StoreSearchTermKind>,
    val terms: List<StoreSearchTermEntry>,
) {
    init {
        require(kinds.isNotEmpty()) { "At least one term kind must be replaced" }
        require(terms.all { it.kind in kinds }) { "Every term must belong to a replaced kind" }
    }
}

/**
 * Replaces the `BRAND_NAME` term of many stores at once, which is what a brand rename needs.
 *
 * `brandName` is null when the brand was unassigned or archived: the stores keep every other term
 * and lose only their brand term.
 */
data class ReplaceBrandSearchTermsCommand(
    val storeIds: List<UUID>,
    val brandName: String?,
) {
    init {
        require(storeIds.distinct().size == storeIds.size) { "Store ids must be distinct" }
    }
}

/**
 * The store search index write port.
 *
 * ADR-112 places this port in `shared/api` on purpose. `merchant` owns brands and regions and must
 * update the index in the same transaction, while `discovery` owns the index and must read store
 * state. Wiring both directly would make Spring Modulith fail on a `merchant` to `discovery` cycle.
 *
 * Implementations join the caller's transaction. A failed index update rolls the whole command
 * back rather than leaving data that the search cannot see (implementation invariant 11).
 */
interface StoreSearchIndexOperations {
    fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand)

    fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand)
}
