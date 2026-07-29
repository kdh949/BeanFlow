package io.github.kdh949.beanflow.ordering.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object CanonicalPaymentPayload {
	fun hash(orderId: UUID, paymentMethodId: UUID): String {
		val canonical = "${orderId.toString().lowercase()}:${paymentMethodId.toString().lowercase()}"
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(StandardCharsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}
