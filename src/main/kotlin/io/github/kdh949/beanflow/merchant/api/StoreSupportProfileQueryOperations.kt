package io.github.kdh949.beanflow.merchant.api

import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import java.util.UUID

interface StoreSupportProfileQueryOperations {
    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedStoreSupportProfile>

    /** Returns only the persisted masked Support projection; raw store data is never exposed. */
    fun findMaskedById(storeId: UUID): MaskedStoreSupportProfile?
}

data class MaskedStoreSupportProfile(
    val storeId: UUID,
    val maskedDisplayName: String,
    val maskedMatchedValue: String,
) {
    override fun toString(): String = "MaskedStoreSupportProfile(storeId=$storeId, values=<redacted>)"
}
