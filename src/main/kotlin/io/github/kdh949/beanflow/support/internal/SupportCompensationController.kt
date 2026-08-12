package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationBenefitType
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationEvidenceBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCompensationResponsibility
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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

internal data class EvaluateSupportCompensationRequest(
    @field:NotNull
    val incidentId: UUID?,
    val orderId: UUID?,
    @field:PositiveOrZero
    val expectedTargetVersion: Long,
    @field:NotNull
    val benefitType: SupportCompensationBenefitType?,
    @field:Positive
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    @field:NotNull
    val responsibility: SupportCompensationResponsibility?,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val costEvidenceDigest: String?,
    @field:Min(0) @field:Max(10_000)
    val platformShareBps: Int,
    @field:Min(0) @field:Max(10_000)
    val storeShareBps: Int,
    @field:NotNull
    val verificationSessionId: UUID?,
) : StrictSupportRequest

internal data class CreateSupportCompensationRequest(
    @field:NotNull
    val incidentId: UUID?,
    val orderId: UUID?,
    @field:PositiveOrZero
    val expectedTargetVersion: Long,
    @field:NotNull
    val benefitType: SupportCompensationBenefitType?,
    @field:Positive
    val amountKrw: Long,
    val couponTemplateId: UUID?,
    @field:NotNull
    val responsibility: SupportCompensationResponsibility?,
    val evidenceBasis: SupportCompensationEvidenceBasis?,
    @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val costEvidenceDigest: String?,
    @field:Min(0) @field:Max(10_000)
    val platformShareBps: Int,
    @field:Min(0) @field:Max(10_000)
    val storeShareBps: Int,
    @field:NotNull
    val verificationSessionId: UUID?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val evidenceDigest: String?,
) : StrictSupportRequest

internal data class ExecuteSupportCompensationRequest(
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:PositiveOrZero
    val expectedTargetVersion: Long,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val expectedPayloadDigest: String?,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportCompensationController(
    private val service: SupportCompensationApplicationService,
) {
    @PostMapping("/cases/{caseId}/compensation-evaluations")
    @PreAuthorize("isAuthenticated()")
    fun evaluate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: EvaluateSupportCompensationRequest,
    ): ResponseEntity<SupportCompensationEvaluationResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.evaluate(
                    EvaluateSupportCompensationCommand(
                        jwt.compensationActorId(),
                        caseId,
                        request.incidentId ?: invalidCompensationRequest(),
                        request.orderId,
                        request.expectedTargetVersion,
                        request.benefitType ?: invalidCompensationRequest(),
                        request.amountKrw,
                        request.couponTemplateId,
                        request.responsibility ?: invalidCompensationRequest(),
                        request.evidenceBasis,
                        request.costEvidenceDigest,
                        request.platformShareBps,
                        request.storeShareBps,
                        request.verificationSessionId ?: invalidCompensationRequest(),
                    ),
                ),
            )

    @PostMapping("/cases/{caseId}/compensations")
    @PreAuthorize("isAuthenticated()")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateSupportCompensationRequest,
    ): ResponseEntity<SupportCompensationResource> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(
                service.create(
                    CreateSupportCompensationCommand(
                        jwt.compensationActorId(),
                        caseId,
                        request.incidentId ?: invalidCompensationRequest(),
                        request.orderId,
                        request.expectedTargetVersion,
                        request.benefitType ?: invalidCompensationRequest(),
                        request.amountKrw,
                        request.couponTemplateId,
                        request.responsibility ?: invalidCompensationRequest(),
                        request.evidenceBasis,
                        request.costEvidenceDigest,
                        request.platformShareBps,
                        request.storeShareBps,
                        request.verificationSessionId ?: invalidCompensationRequest(),
                        request.evidenceDigest ?: invalidCompensationRequest(),
                        idempotencyKey,
                    ),
                ),
            )

    @GetMapping("/compensations/{compensationRequestId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable compensationRequestId: UUID,
    ): ResponseEntity<SupportCompensationResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.get(jwt.compensationActorId(), compensationRequestId))

    @PostMapping("/compensations/{compensationRequestId}/executions")
    @PreAuthorize("isAuthenticated()")
    fun execute(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable compensationRequestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ExecuteSupportCompensationRequest,
    ): ResponseEntity<SupportCompensationResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.execute(
                    ExecuteSupportCompensationCommand(
                        jwt.compensationActorId(),
                        compensationRequestId,
                        request.expectedRequestVersion,
                        request.expectedTargetVersion,
                        request.expectedPayloadDigest ?: invalidCompensationRequest(),
                        idempotencyKey,
                    ),
                ),
            )

    @PostMapping("/compensations/{compensationRequestId}/notification-retries")
    @PreAuthorize("isAuthenticated()")
    fun retryNotification(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable compensationRequestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<SupportCompensationResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.retryNotification(
                    RetrySupportCompensationNotificationCommand(
                        jwt.compensationActorId(),
                        compensationRequestId,
                        idempotencyKey,
                    ),
                ),
            )
}

private fun Jwt.compensationActorId(): UUID =
    try {
        UUID.fromString(subject)
    } catch (_: IllegalArgumentException) {
        invalidCompensationRequest()
    }

private fun invalidCompensationRequest(): Nothing =
    throw DomainFailure(FailureCode.INVALID_REQUEST, "Goodwill compensation request is invalid")
