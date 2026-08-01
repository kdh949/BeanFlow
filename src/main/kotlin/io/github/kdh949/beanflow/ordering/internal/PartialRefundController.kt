package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
    @PreAuthorize("hasAnyRole('STORE_OWNER', 'STORE_STAFF', 'PLATFORM_OPERATOR')")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
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
                    actor = actor(jwt),
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

    private fun actor(jwt: Jwt): PartialRefundActor {
        val actorId =
            try {
                UUID.fromString(jwt.subject)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid actor ID")
            }
        val claims = jwt.getClaimAsStringList("roles").orEmpty()
        val types =
            buildSet {
                if ("STORE_OWNER" in claims) add(PartialRefundActorType.STORE_OWNER)
                if ("STORE_STAFF" in claims) add(PartialRefundActorType.STORE_STAFF)
                if ("PLATFORM_OPERATOR" in claims) add(PartialRefundActorType.PLATFORM_OPERATOR)
            }
        if (types.isEmpty()) throw DomainFailure(FailureCode.ACCESS_DENIED, "Refund actor role is required")
        return PartialRefundActor(actorId, types)
    }
}
