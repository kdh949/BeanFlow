package io.github.kdh949.beanflow.payment.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class RegisterPaymentMethodRequest(
    @field:Size(min = 1, max = 300)
    val authKey: String,
    val displayAlias: String,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown payment method registration field")
}

@Validated
@RestController
@RequestMapping("/api/v1/payment-methods")
internal class PaymentMethodController(
    private val application: PaymentMethodApplicationService,
    private val queries: PaymentMethodQueryService,
) {
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: String?,
    ): PaymentMethodPage = queries.list(customerId(jwt), cursor, limit)

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun register(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: RegisterPaymentMethodRequest,
    ): ResponseEntity<String> =
        application
            .register(
                RegisterPaymentMethodCommand(
                    customerId = customerId(jwt),
                    idempotencyKey = idempotencyKey,
                    authorizationKey = request.authKey,
                    displayAlias = request.displayAlias,
                ),
            ).toResponse()

    @DeleteMapping("/{paymentMethodId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun deactivate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable paymentMethodId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<String> =
        application.deactivate(customerId(jwt), paymentMethodId, idempotencyKey).toResponse()

    @PutMapping("/{paymentMethodId}/default")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun setDefault(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable paymentMethodId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<String> =
        application.setDefault(customerId(jwt), paymentMethodId, idempotencyKey).toResponse()

    private fun PaymentMethodHttpResult.toResponse(): ResponseEntity<String> =
        if (status == 204) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        }

    private fun customerId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}
