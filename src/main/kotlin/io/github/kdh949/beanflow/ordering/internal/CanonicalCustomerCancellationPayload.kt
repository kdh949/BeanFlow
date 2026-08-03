package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

internal object CanonicalCustomerCancellationPayload {
    fun normalizeDetail(detail: String?): String? {
        val normalized = detail?.trim()?.ifEmpty { null } ?: return null
        if (normalized.codePointCount(0, normalized.length) > 200 || normalized.any(Char::isISOControl)) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Cancellation detail must contain at most 200 non-control characters",
            )
        }
        return normalized
    }

    fun hash(
        orderId: UUID,
        reasonCode: CustomerCancellationReasonCode,
        normalizedDetail: String?,
        objectMapper: ObjectMapper,
    ): String {
        val canonical: SortedMap<String, Any?> =
            TreeMap<String, Any?>().apply {
                put("detail", normalizedDetail)
                put("orderId", orderId)
                put("reasonCode", reasonCode.name)
            }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonical)),
        )
    }
}
