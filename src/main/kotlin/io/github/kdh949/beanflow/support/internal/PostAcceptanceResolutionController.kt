package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionOutcome
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionResponsibility
import io.github.kdh949.beanflow.support.internal.domain.PostAcceptanceResolutionStepType
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

internal data class CreatePostAcceptanceResolutionRequest(
    @field:NotNull
    val requestId: UUID?,
    @field:Positive
    val revisionNumber: Int,
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:PositiveOrZero
    val expectedOrderVersion: Long,
    @field:NotNull
    val outcome: PostAcceptanceResolutionOutcome?,
    @field:NotNull
    val responsibility: PostAcceptanceResolutionResponsibility?,
    @field:PositiveOrZero
    val cashRefundKrw: Long,
    val restorePoints: Boolean,
    val restoreCoupon: Boolean,
    val settlementAdjustmentKrw: Long?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val evidenceDigest: String?,
) : StrictSupportRequest

internal data class ExecutePostAcceptanceResolutionRequest(
    @field:PositiveOrZero
    val expectedResolutionVersion: Long,
    @field:PositiveOrZero
    val expectedRequestVersion: Long,
    @field:PositiveOrZero
    val expectedOrderVersion: Long,
) : StrictSupportRequest

internal data class ReconcilePostAcceptanceResolutionRequest(
    @field:NotNull
    val stepType: PostAcceptanceResolutionStepType?,
    @field:PositiveOrZero
    val expectedResolutionVersion: Long,
    @field:PositiveOrZero
    val expectedOrderVersion: Long,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class PostAcceptanceResolutionController(
    private val service: PostAcceptanceResolutionApplicationService,
) {
    @PostMapping("/orders/{orderId}/post-acceptance-resolutions")
    @PreAuthorize("isAuthenticated()")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreatePostAcceptanceResolutionRequest,
    ): ResponseEntity<PostAcceptanceResolutionResource> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(
                service.create(
                    CreatePostAcceptanceResolutionCommand(
                        jwt.resolutionActorId(),
                        orderId,
                        request.requestId ?: invalidResolutionRequest(),
                        request.revisionNumber,
                        request.expectedRequestVersion,
                        request.expectedOrderVersion,
                        request.outcome ?: invalidResolutionRequest(),
                        request.responsibility ?: invalidResolutionRequest(),
                        request.cashRefundKrw,
                        request.restorePoints,
                        request.restoreCoupon,
                        request.settlementAdjustmentKrw,
                        request.evidenceDigest ?: invalidResolutionRequest(),
                        idempotencyKey,
                    ),
                ),
            )

    @GetMapping("/post-acceptance-resolutions/{resolutionId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable resolutionId: UUID,
    ): ResponseEntity<PostAcceptanceResolutionResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.get(jwt.resolutionActorId(), resolutionId))

    @PostMapping("/post-acceptance-resolutions/{resolutionId}/executions")
    @PreAuthorize("isAuthenticated()")
    fun execute(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable resolutionId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ExecutePostAcceptanceResolutionRequest,
    ): ResponseEntity<PostAcceptanceResolutionResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.execute(
                    ExecutePostAcceptanceResolutionCommand(
                        jwt.resolutionActorId(),
                        resolutionId,
                        request.expectedResolutionVersion,
                        request.expectedRequestVersion,
                        request.expectedOrderVersion,
                        idempotencyKey,
                    ),
                ),
            )

    @PostMapping("/post-acceptance-resolutions/{resolutionId}/reconciliations")
    @PreAuthorize("isAuthenticated()")
    fun reconcile(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable resolutionId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ReconcilePostAcceptanceResolutionRequest,
    ): ResponseEntity<PostAcceptanceResolutionResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.reconcile(
                    ReconcilePostAcceptanceResolutionCommand(
                        jwt.resolutionActorId(),
                        resolutionId,
                        request.stepType ?: invalidResolutionRequest(),
                        request.expectedResolutionVersion,
                        request.expectedOrderVersion,
                        idempotencyKey,
                    ),
                ),
            )
}

private fun Jwt.resolutionActorId(): UUID =
    try {
        UUID.fromString(subject)
    } catch (_: IllegalArgumentException) {
        invalidResolutionRequest()
    }

private fun invalidResolutionRequest(): Nothing =
    throw DomainFailure(FailureCode.INVALID_REQUEST, "Post-Acceptance Resolution request is invalid")
