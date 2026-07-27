package io.github.kdh949.beanflow.ordering.api

import java.util.UUID

data class CreateOrderLineCommand(
	val menuId: UUID,
	val optionIds: List<UUID>,
	val quantity: Long,
)

data class CreateOrderCommand(
	val customerId: UUID,
	val storeId: UUID,
	val pickupSlotId: UUID,
	val lines: List<CreateOrderLineCommand>,
	val couponIssuanceId: UUID?,
	val pointsToUseKrw: Long,
)

data class StoredHttpResponse(
	val status: Int,
	val body: String,
	val retryAfterSeconds: Long? = null,
	val replay: Boolean = false,
)

interface CreateOrderUseCase {
	fun create(idempotencyKey: String, command: CreateOrderCommand): StoredHttpResponse
}
