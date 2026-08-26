package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object CanonicalOrderPayload {
    fun hash(command: CreateOrderCommand): String {
        val lines =
            command.lines.joinToString(",", prefix = "[", postfix = "]") { line ->
                val options =
                    line.optionIds.sorted().joinToString(",", prefix = "[", postfix = "]") {
                        "\"$it\""
                    }
                """{"menuId":"${line.menuId}","optionIds":$options,"quantity":${line.quantity}}"""
            }
        val canonical =
            """{"couponIssuanceId":${command.couponIssuanceId?.let { "\"$it\"" } ?: "null"},""" +
                """"expectedQuoteFingerprint":${command.expectedQuoteFingerprint?.let { "\"$it\"" } ?: "null"},""" +
                """"lines":$lines,"pickupSlotId":"${command.pickupSlotId}",""" +
                """"pointsToUseKrw":${command.pointsToUseKrw},"storeId":"${command.storeId}"}"""
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
