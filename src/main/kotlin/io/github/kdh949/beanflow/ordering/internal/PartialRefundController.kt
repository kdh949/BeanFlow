package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CreateRefundRequest(
    val lineItems: List<@Valid RefundLineRequest>? = null,
    @field:Size(min = 1, max = 500)
    val reason: String,
)

internal data class RefundLineRequest(
    val orderLineId: UUID,
    @field:Min(1)
    val quantity: Long,
)

@Validated
@RestController
@RequestMapping("/api/v1/payments")
internal class PartialRefundController(
    private val service: PartialRefundService,
) {
    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("isAuthenticated()")
    fun create(
        actor: MerchantActor,
        @PathVariable paymentId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateRefundRequest,
    ): ResponseEntity<String> {
        if (request.lineItems?.isEmpty() == true) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "lineItems must be omitted or contain at least one item")
        }
        val result =
            service.create(
                PartialRefundCommand(
                    paymentId = paymentId,
                    actor =
                        PartialRefundActor(
                            actor.actorId,
                            setOf(PartialRefundActorType.STORE_OWNER, PartialRefundActorType.STORE_STAFF),
                        ),
                    idempotencyKey = idempotencyKey,
                    lines = request.lineItems?.map { PartialRefundLineInput(it.orderLineId, it.quantity) },
                    reason = request.reason,
                ),
            )
        return ResponseEntity
            .status(result.status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(result.body)
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/payments")
internal class OperationsPartialRefundController(
    private val service: PartialRefundService,
) {
    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun create(
        actor: OperatorActor,
        @PathVariable paymentId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateRefundRequest,
    ): ResponseEntity<String> {
        if (request.lineItems?.isEmpty() == true) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "lineItems must be omitted or contain at least one item")
        }
        val result =
            service.create(
                PartialRefundCommand(
                    paymentId = paymentId,
                    actor = PartialRefundActor(actor.actorId, setOf(PartialRefundActorType.PLATFORM_OPERATOR)),
                    idempotencyKey = idempotencyKey,
                    lines = request.lineItems?.map { PartialRefundLineInput(it.orderLineId, it.quantity) },
                    reason = request.reason,
                ),
            )
        return ResponseEntity
            .status(result.status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(result.body)
    }
}
