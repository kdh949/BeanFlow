package io.github.kdh949.beanflow.ordering.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object CanonicalStoreOrderTransitionPayload {
    fun hash(
        orderId: UUID,
        targetState: StoreOrderTargetState,
        reason: String?,
    ): String {
        val canonical = "$orderId|${targetState.name}|${reason?.trim().orEmpty()}"
        return digest(canonical)
    }

    fun hashBoardAction(
        orderId: UUID,
        request: StoreOrderActionRequest,
    ): String {
        val canonical =
            "$orderId|${request.action.name}|${request.expectedStatus.name}|${request.reason?.trim().orEmpty()}"
        return digest(canonical)
    }

    private fun digest(canonical: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
