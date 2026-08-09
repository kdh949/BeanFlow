package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object CanonicalReorderPayload {
    fun hash(command: ReorderOrderCommand): String {
        val canonical =
            """{"couponIssuanceId":${command.couponIssuanceId?.let { "\"$it\"" } ?: "null"},""" +
                """"pickupSlotId":"${command.pickupSlotId}","pointsToUseKrw":${command.pointsToUseKrw},""" +
                """"sourceOrderId":"${command.sourceOrderId}"}"""
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
