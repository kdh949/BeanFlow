package io.github.kdh949.beanflow.identity.api

import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import java.util.UUID

interface CustomerSupportProfileQueryOperations {
    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedCustomerSupportProfile>
}

data class MaskedCustomerSupportProfile(
    val customerId: UUID,
    val maskedDisplayName: String,
    val maskedMatchedValue: String,
) {
    override fun toString(): String = "MaskedCustomerSupportProfile(customerId=$customerId, values=<redacted>)"
}
