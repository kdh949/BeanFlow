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
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
