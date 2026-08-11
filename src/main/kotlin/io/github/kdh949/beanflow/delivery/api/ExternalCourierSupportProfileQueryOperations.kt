package io.github.kdh949.beanflow.delivery.api

import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import java.util.UUID

interface ExternalCourierSupportProfileQueryOperations {
    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedExternalCourierSupportProfile>
}

data class MaskedExternalCourierSupportProfile(
    val externalCourierId: UUID,
    val maskedDisplayName: String,
    val maskedMatchedValue: String,
) {
    override fun toString(): String = "MaskedExternalCourierSupportProfile(externalCourierId=$externalCourierId, values=<redacted>)"
}
