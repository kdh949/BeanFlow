package io.github.kdh949.beanflow.shared.api

enum class FailureCode {
	INVALID_REQUEST,
	ACCESS_DENIED,
	RESOURCE_NOT_FOUND,
	MENU_CONFIGURATION_NOT_AVAILABLE,
	PICKUP_SLOT_FULL,
	STOCK_NOT_AVAILABLE,
	COUPON_NOT_AVAILABLE,
	POINT_BALANCE_INSUFFICIENT,
	ORDER_STATE_CONFLICT,
	IDEMPOTENCY_KEY_REUSED,
	IDEMPOTENCY_REQUEST_IN_PROGRESS,
	RESERVATION_EXPIRED,
	DEPENDENCY_UNAVAILABLE,
}

class DomainFailure(
	val code: FailureCode,
	override val message: String,
	val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
