package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateOrderRequest(
	val storeId: UUID,
	val pickupSlotId: UUID,
	@field:NotEmpty
	val lines: List<@Valid CreateOrderLineRequest>,
	val couponIssuanceId: UUID?,
	@field:Min(0)
	val pointsToUseKrw: Long,
)

data class CreateOrderLineRequest(
	val menuId: UUID,
	val optionIds: List<UUID>,
	@field:Min(1)
	val quantity: Long,
)

@Validated
@RestController
@RequestMapping("/api/v1/orders")
internal class OrderController(
	private val createOrderUseCase: CreateOrderUseCase,
) {

	@PostMapping
	@PreAuthorize("hasRole('CUSTOMER')")
	fun create(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
		@Valid @RequestBody request: CreateOrderRequest,
	): ResponseEntity<String> {
		val customerId = try {
			UUID.fromString(jwt.subject)
		} catch (_: IllegalArgumentException) {
			throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
		}
		val result = createOrderUseCase.create(
			idempotencyKey = idempotencyKey,
			command = CreateOrderCommand(
				customerId = customerId,
				storeId = request.storeId,
				pickupSlotId = request.pickupSlotId,
				lines = request.lines.map {
					CreateOrderLineCommand(it.menuId, it.optionIds, it.quantity)
				},
				couponIssuanceId = request.couponIssuanceId,
				pointsToUseKrw = request.pointsToUseKrw,
			),
		)
		val response = ResponseEntity.status(result.status)
			.contentType(MediaType.APPLICATION_JSON)
		result.retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
		return response.body(result.body)
	}
}
