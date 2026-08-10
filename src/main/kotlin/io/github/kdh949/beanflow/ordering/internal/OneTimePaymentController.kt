package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class ConfirmOneTimePaymentRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val paymentKey: String,
    @field:NotBlank
    @field:Size(min = 6, max = 64)
    val orderId: String,
    @field:Min(1)
    val amount: Long,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown payment confirmation field")
}

internal data class PaymentClientConfigurationResponse(
    val provider: String,
    val sdkVersion: String,
    val clientKey: String,
)

@Validated
@RestController
@RequestMapping("/api/v1")
internal class OneTimePaymentController(
    private val checkout: OneTimeCheckoutService,
    @Value("\${beanflow.toss.client-key:}")
    private val tossClientKey: String,
) {
    @GetMapping("/payment-config")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun configuration(): PaymentClientConfigurationResponse {
        if (tossClientKey.isBlank()) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Toss payment client key is not configured")
        }
        return PaymentClientConfigurationResponse(
            provider = "TOSS_PAYMENTS",
            sdkVersion = "V2_STANDARD",
            clientKey = tossClientKey,
        )
    }

    @PostMapping("/payments/{paymentId}/confirmations")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun confirm(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable paymentId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ConfirmOneTimePaymentRequest,
    ): ResponseEntity<String> {
        val result =
            checkout.confirm(
                customerId = customerId(jwt),
                paymentId = paymentId,
                idempotencyKey = idempotencyKey,
                request = OneTimePaymentConfirmationRequest(request.paymentKey, request.orderId, request.amount),
            )
        return ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body)
    }

    @GetMapping("/payments/{paymentId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable paymentId: UUID,
    ): ResponseEntity<String> {
        val result = checkout.current(customerId(jwt), paymentId)
        return ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body)
    }

    private fun customerId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}
