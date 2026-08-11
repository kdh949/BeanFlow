package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportApprovalDecision
import jakarta.validation.Valid
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

internal data class CreateSupportActionRequestRequest(
    @field:NotNull
    val action: SupportActionType?,
    @field:NotNull
    val orderId: UUID?,
    @field:NotNull @field:PositiveOrZero
    val expectedTargetVersion: Long?,
    @field:NotNull
    val verificationSessionId: UUID?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val actionPayloadDigest: String?,
    @field:PositiveOrZero
    val amountKrw: Long?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val evidenceDigest: String?,
) : StrictSupportRequest

internal data class ReviseSupportActionRequestRequest(
    @field:Positive
    val expectedRevisionNumber: Int,
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:PositiveOrZero
    val expectedTargetVersion: Long,
    @field:NotNull
    val verificationSessionId: UUID?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val actionPayloadDigest: String?,
    @field:PositiveOrZero
    val amountKrw: Long?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val evidenceDigest: String?,
) : StrictSupportRequest

internal data class DecideSupportManagerApprovalRequest(
    @field:Positive
    val revisionNumber: Int,
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:NotNull
    val decision: SupportApprovalDecision?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String?,
) : StrictSupportRequest

internal data class ReassignSupportActionRequestRequest(
    @field:Positive
    val revisionNumber: Int,
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:PositiveOrZero
    val expectedCaseVersion: Long,
    @field:NotNull
    val assigneeId: UUID?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String?,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportActionRequestController(
    private val service: SupportActionRequestApplicationService,
) {
    @PostMapping("/cases/{caseId}/action-requests")
    @PreAuthorize("isAuthenticated()")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: CreateSupportActionRequestRequest,
    ): ResponseEntity<SupportActionRequestResource> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(
                service.create(
                    CreateSupportActionRequestCommand(
                        jwt.actorId(),
                        caseId,
                        request.action ?: invalid(),
                        request.orderId ?: invalid(),
                        request.expectedTargetVersion ?: invalid(),
                        request.verificationSessionId ?: invalid(),
                        request.actionPayloadDigest ?: invalid(),
                        request.amountKrw,
                        request.reason ?: invalid(),
                        request.evidenceDigest ?: invalid(),
                        idempotencyKey,
                    ),
                ),
            )

    @GetMapping("/action-requests/{requestId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable requestId: UUID,
    ): ResponseEntity<SupportActionRequestResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.get(jwt.actorId(), requestId))

    @PostMapping("/action-requests/{requestId}/revisions")
    @PreAuthorize("isAuthenticated()")
    fun revise(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable requestId: UUID,
        @Valid @RequestBody request: ReviseSupportActionRequestRequest,
    ): ResponseEntity<SupportActionRequestResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.revise(
                    ReviseSupportActionRequestCommand(
                        jwt.actorId(),
                        requestId,
                        request.expectedRevisionNumber,
                        request.expectedRequestVersion,
                        request.expectedTargetVersion,
                        request.verificationSessionId ?: invalid(),
                        request.actionPayloadDigest ?: invalid(),
                        request.amountKrw,
                        request.reason ?: invalid(),
                        request.evidenceDigest ?: invalid(),
                        idempotencyKey,
                    ),
                ),
            )

    @PostMapping("/action-requests/{requestId}/support-manager-decisions")
    @PreAuthorize("isAuthenticated()")
    fun decideSupportManager(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable requestId: UUID,
        @Valid @RequestBody request: DecideSupportManagerApprovalRequest,
    ): ResponseEntity<SupportActionRequestResource> {
        val outcome =
            service.decideSupportManager(
                DecideSupportManagerApprovalCommand(
                    jwt.actorId(),
                    requestId,
                    request.revisionNumber,
                    request.expectedRequestVersion,
                    request.decision ?: invalid(),
                    request.reason ?: invalid(),
                    idempotencyKey,
                ),
            )
        val resource =
            when (outcome) {
                is SupportActionCommandOutcome.Succeeded -> outcome.resource
                is SupportActionCommandOutcome.Failed -> throw DomainFailure(outcome.code, outcome.message)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resource)
    }

    @PostMapping("/action-requests/{requestId}/reassignments")
    @PreAuthorize("isAuthenticated()")
    fun reassign(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable requestId: UUID,
        @Valid @RequestBody request: ReassignSupportActionRequestRequest,
    ): ResponseEntity<SupportActionRequestResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.reassign(
                    ReassignSupportActionRequestCommand(
                        jwt.actorId(),
                        requestId,
                        request.revisionNumber,
                        request.expectedRequestVersion,
                        request.expectedCaseVersion,
                        request.assigneeId ?: invalid(),
                        request.reason ?: invalid(),
                        idempotencyKey,
                    ),
                ),
            )

    private fun Jwt.actorId(): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support action request is invalid")
}
