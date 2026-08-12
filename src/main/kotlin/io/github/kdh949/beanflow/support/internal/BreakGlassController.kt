package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassReasonCode
import io.github.kdh949.beanflow.support.internal.domain.BreakGlassReviewDecision
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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

internal data class RequestBreakGlassRequest(
    @field:NotNull
    val subjectLinkId: UUID?,
    @field:NotNull
    val field: SupportPersonalDataField?,
    @field:NotNull
    val purpose: VerificationPurpose?,
    @field:NotNull
    val reasonCode: BreakGlassReasonCode?,
) : StrictSupportRequest

internal data class DecideBreakGlassRequest(
    @field:NotNull
    val decision: BreakGlassApprovalDecision?,
    @field:PositiveOrZero
    val expectedVersion: Long,
) : StrictSupportRequest

internal data class RevealBreakGlassRequest(
    @field:NotNull
    val field: SupportPersonalDataField?,
) : StrictSupportRequest

internal data class ReviewBreakGlassRequest(
    @field:NotNull
    val decision: BreakGlassReviewDecision?,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 32)
    val reasonCode: String,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class BreakGlassController(
    private val service: BreakGlassApplicationService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping("/cases/{caseId}/break-glass-requests")
    @PreAuthorize("isAuthenticated()")
    fun request(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: RequestBreakGlassRequest,
    ): ResponseEntity<BreakGlassResource> =
        noStore(
            HttpStatus.CREATED,
            service.request(
                RequestBreakGlassCommand(
                    jwt.actorId(),
                    caseId,
                    request.subjectLinkId ?: invalid(),
                    request.field ?: invalid(),
                    request.purpose ?: invalid(),
                    request.reasonCode ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/break-glass-requests/{requestId}/approvals")
    @PreAuthorize("isAuthenticated()")
    fun decide(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: DecideBreakGlassRequest,
    ): ResponseEntity<BreakGlassResource> =
        noStore(
            HttpStatus.OK,
            service.decide(
                DecideBreakGlassCommand(
                    jwt.actorId(),
                    requestId,
                    request.decision ?: invalid(),
                    request.expectedVersion,
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/break-glass-requests/{requestId}/reveals")
    @PreAuthorize("isAuthenticated()")
    fun reveal(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: RevealBreakGlassRequest,
    ): ResponseEntity<BreakGlassRevealResource> =
        noStore(
            HttpStatus.OK,
            service.reveal(
                RevealBreakGlassCommand(
                    jwt.actorId(),
                    requestId,
                    request.field ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/break-glass-requests/{requestId}/reviews")
    @PreAuthorize("isAuthenticated()")
    fun review(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ReviewBreakGlassRequest,
    ): ResponseEntity<BreakGlassResource> =
        noStore(
            HttpStatus.OK,
            service.review(
                ReviewBreakGlassCommand(
                    jwt.actorId(),
                    requestId,
                    request.decision ?: invalid(),
                    request.expectedVersion,
                    request.reasonCode,
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    private fun Jwt.actorId(): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun <T : Any> noStore(
        status: HttpStatus,
        body: T,
    ): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Break-glass request is invalid")
}
