package io.github.kdh949.beanflow.ordering.api

import java.time.Instant
import java.util.UUID

enum class ReservationExpiryOutcome {
	EXPIRED,
	NOT_ELIGIBLE,
}

data class ReservationExpiryResult(
	val orderId: UUID,
	val outcome: ReservationExpiryOutcome,
)

interface ReservationExpiryUseCase {
	fun expireIfDue(orderId: UUID, now: Instant): ReservationExpiryResult
}
