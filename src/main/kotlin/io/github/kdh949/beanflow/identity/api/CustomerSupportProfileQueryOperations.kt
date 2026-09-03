package io.github.kdh949.beanflow.identity.api

import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import java.util.UUID

interface CustomerSupportProfileQueryOperations {
    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedCustomerSupportProfile>

    /** Returns only the persisted masked Support projection; raw profile data is never exposed. */
    fun findMaskedById(customerId: UUID): MaskedCustomerSupportProfile?
}

data class MaskedCustomerSupportProfile(
    val customerId: UUID,
    val maskedDisplayName: String,
    val maskedMatchedValue: String,
) {
    override fun toString(): String = "MaskedCustomerSupportProfile(customerId=$customerId, values=<redacted>)"
}
