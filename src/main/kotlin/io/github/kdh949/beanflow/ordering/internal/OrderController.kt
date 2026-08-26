package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.OrderQuoteCommand
import io.github.kdh949.beanflow.ordering.api.OrderQuoteResponse
import io.github.kdh949.beanflow.ordering.api.OrderQuoteUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderCommand
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val expectedQuoteFingerprint: String,
)

data class OrderQuoteRequest(
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

data class ReorderOrderRequest(
    val pickupSlotId: UUID,
    val couponIssuanceId: UUID?,
    @field:Min(0)
    val pointsToUseKrw: Long,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown reorder request field")
}

@Validated
@RestController
@RequestMapping("/api/v1/orders")
internal class OrderController(
    private val createOrderUseCase: CreateOrderUseCase,
    private val reorderOrderUseCase: ReorderOrderUseCase,
    private val getOrderService: GetOrderService,
    private val oneTimeCheckoutService: OneTimeCheckoutService,
    private val customerCancellationService: CustomerCancellationService,
) {
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun create(
        actor: CustomerActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateOrderRequest,
    ): ResponseEntity<String> {
        val customerId = customerId(actor)
        val result =
            createOrderUseCase.create(
                idempotencyKey = idempotencyKey,
                command =
                    CreateOrderCommand(
                        customerId = customerId,
                        storeId = request.storeId,
                        pickupSlotId = request.pickupSlotId,
                        lines =
                            request.lines.map {
                                CreateOrderLineCommand(it.menuId, it.optionIds, it.quantity)
                            },
                        couponIssuanceId = request.couponIssuanceId,
                        pointsToUseKrw = request.pointsToUseKrw,
                        expectedQuoteFingerprint = request.expectedQuoteFingerprint,
                    ),
            )
        val response =
            ResponseEntity
                .status(result.status)
                .contentType(MediaType.APPLICATION_JSON)
        result.retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.body(result.body)
    }

    @PostMapping("/{sourceOrderId}/reorders")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun reorder(
        actor: CustomerActor,
        @PathVariable sourceOrderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ReorderOrderRequest,
    ): ResponseEntity<String> {
        val result =
            reorderOrderUseCase.reorder(
                idempotencyKey = idempotencyKey,
                command =
                    ReorderOrderCommand(
                        customerId = customerId(actor),
                        sourceOrderId = sourceOrderId,
                        pickupSlotId = request.pickupSlotId,
                        couponIssuanceId = request.couponIssuanceId,
                        pointsToUseKrw = request.pointsToUseKrw,
                    ),
            )
        val response =
            ResponseEntity
                .status(result.status)
                .contentType(MediaType.APPLICATION_JSON)
        result.retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.body(result.body)
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun get(
        actor: CustomerActor,
        @PathVariable orderId: UUID,
    ): OrderResponse = getOrderService.get(customerId(actor), orderId)

    @PostMapping("/{orderId}/cancellations")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun cancel(
        actor: CustomerActor,
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CustomerCancellationRequest,
    ): ResponseEntity<String> {
        val result =
            customerCancellationService.cancel(
                customerId = customerId(actor),
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                request = request,
            )
        return ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body)
    }

    @PostMapping("/{orderId}/payment-attempts")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun preparePayment(
        actor: CustomerActor,
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ) = oneTimeCheckoutService.prepare(customerId(actor), orderId, idempotencyKey)

    private fun customerId(actor: CustomerActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}

@Validated
@RestController
@RequestMapping("/api/v1/me/order-quotes")
internal class OrderQuoteController(
    private val orderQuoteUseCase: OrderQuoteUseCase,
) {
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun quote(
        actor: CustomerActor,
        @Valid @RequestBody request: OrderQuoteRequest,
    ): OrderQuoteResponse =
        orderQuoteUseCase.quote(
            OrderQuoteCommand(
                customerId = customerId(actor),
                storeId = request.storeId,
                pickupSlotId = request.pickupSlotId,
                lines = request.lines.map { CreateOrderLineCommand(it.menuId, it.optionIds, it.quantity) },
                couponIssuanceId = request.couponIssuanceId,
                pointsToUseKrw = request.pointsToUseKrw,
            ),
        )

    private fun customerId(actor: CustomerActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}
