package io.github.kdh949.beanflow.shared.api

import java.util.UUID

enum class ReservationTransitionResult {
	APPLIED,
	ALREADY_APPLIED,
	NOT_ELIGIBLE,
}

data class ReservationTransitionReport(
	val result: ReservationTransitionResult,
	val targetIds: List<UUID>,
)
