package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.operations.api.OperationsSupportInvestigationDecision
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
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
import java.time.Clock
import java.util.UUID

internal data class DecideOperationsSupportInvestigationRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotNull
    val decision: OperationsSupportInvestigationDecision?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val evidenceDigest: String?,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") field: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Operations investigation request contains an unexpected field")
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/investigations")
internal class OperationsSupportInvestigationController(
    private val service: OperationsSupportInvestigationService,
    private val clock: Clock,
) {
    @PostMapping("/{investigationId}/decisions")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun decide(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable investigationId: UUID,
        @Valid @RequestBody request: DecideOperationsSupportInvestigationRequest,
    ): ResponseEntity<OperationsSupportInvestigationDecisionResource> {
        val outcome =
            service.decide(
                DecideOperationsSupportInvestigationCommand(
                    actorId = jwt.actorId(),
                    investigationId = investigationId,
                    expectedVersion = request.expectedVersion,
                    decision = request.decision ?: invalid(),
                    reason = request.reason ?: invalid(),
                    evidenceDigest = request.evidenceDigest ?: invalid(),
                    idempotencyKey = idempotencyKey,
                    now = clock.instant(),
                ),
            )
        val resource =
            when (outcome) {
                is OperationsSupportInvestigationOutcome.Succeeded -> outcome.resource
                is OperationsSupportInvestigationOutcome.Failed -> throw DomainFailure(outcome.code, outcome.message)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resource)
    }

    private fun Jwt.actorId(): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Operations investigation request is invalid")
}
