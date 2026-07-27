package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

interface OrderPaymentLeaseGuard {
	fun requireEligible(customerId: UUID, orderId: UUID, now: Instant)
}
