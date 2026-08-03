package io.github.kdh949.beanflow.loyalty.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.loyalty.api.ApplyPointAdjustmentCommand
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentIssuer
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentOperations
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentResult
import io.github.kdh949.beanflow.loyalty.api.PointIssuerType
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class PointAdjustmentIssuerRequest(
    val issuerType: PointIssuerType,
    @field:NotBlank @field:Size(max = 200)
    val issuerReference: String,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown point adjustment issuer field")
}

internal data class PointAdjustmentRequest(
    val amountKrw: Long,
    @field:Valid
    val issuer: PointAdjustmentIssuerRequest? = null,
    val expiresAt: Instant? = null,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
    @field:NotEmpty
    val evidenceReferences: List<
        @NotBlank
        @Size(max = 500)
        String,
    >,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown point adjustment request field")
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/point-accounts/{accountId}/adjustments")
internal class PointAdjustmentController(
    private val operations: PointAdjustmentOperations,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun adjust(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable accountId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: PointAdjustmentRequest,
    ): PointAdjustmentResult =
        operations.adjust(
            ApplyPointAdjustmentCommand(
                actorId = actorId(jwt),
                pointAccountId = accountId,
                idempotencyKey = idempotencyKey,
                amountKrw = request.amountKrw,
                issuer =
                    request.issuer?.let {
                        PointAdjustmentIssuer(it.issuerType, it.issuerReference)
                    },
                expiresAt = request.expiresAt,
                reason = request.reason,
                evidenceReferences = request.evidenceReferences,
                correlationId = correlationIds.currentOrCreate(),
                now = clock.instant(),
            ),
        )

    private fun actorId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
