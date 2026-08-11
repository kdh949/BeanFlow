package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.identity.api.SensitiveVerificationProof
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
import java.util.Arrays
import java.util.UUID

internal data class CreateVerificationSessionRequest(
    @field:NotNull
    val subjectLinkId: UUID?,
    @field:NotNull
    val requestedLevel: VerificationLevel?,
    @field:NotNull
    val purpose: VerificationPurpose?,
) : StrictSupportRequest

internal data class IssueVerificationChallengeRequest(
    @field:NotNull
    val channel: VerificationChannel?,
) : StrictSupportRequest

internal data class VerifyVerificationChallengeRequest(
    @field:NotBlank @field:Size(max = 512)
    val proof: String,
) : StrictSupportRequest {
    override fun toString(): String = "VerifyVerificationChallengeRequest(proof=<redacted>)"
}

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportVerificationController(
    private val service: SupportVerificationApplicationService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping("/cases/{caseId}/verification-sessions")
    @PreAuthorize("isAuthenticated()")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateVerificationSessionRequest,
    ): ResponseEntity<VerificationSessionResource> =
        noStore(
            HttpStatus.CREATED,
            service.create(
                CreateVerificationSessionCommand(
                    jwt.actorId(),
                    caseId,
                    request.subjectLinkId ?: invalid(),
                    request.requestedLevel?.takeUnless { it == VerificationLevel.UNVERIFIED } ?: invalid(),
                    request.purpose ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @GetMapping("/verification-sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<VerificationSessionResource> = noStore(HttpStatus.OK, service.get(jwt.actorId(), sessionId))

    @PostMapping("/verification-sessions/{sessionId}/challenges")
    @PreAuthorize("isAuthenticated()")
    fun issue(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: IssueVerificationChallengeRequest,
    ): ResponseEntity<VerificationChallengeResource> =
        noStore(
            HttpStatus.CREATED,
            service.issue(
                IssueVerificationChallengeRequestCommand(
                    jwt.actorId(),
                    sessionId,
                    request.channel ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/verification-challenges/{challengeId}/verifications")
    @PreAuthorize("isAuthenticated()")
    fun verify(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable challengeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: VerifyVerificationChallengeRequest,
    ): ResponseEntity<VerificationResultResource> {
        val transientChars = request.proof.toCharArray()
        return try {
            noStore(
                HttpStatus.OK,
                service.verify(
                    VerifySupportChallengeCommand(
                        jwt.actorId(),
                        challengeId,
                        SensitiveVerificationProof.copyOf(transientChars),
                        idempotencyKey,
                        correlationIds.currentOrCreate(),
                    ),
                ),
            )
        } finally {
            Arrays.fill(transientChars, '\u0000')
        }
    }

    @PostMapping("/verification-sessions/{sessionId}/revocations")
    @PreAuthorize("isAuthenticated()")
    fun revoke(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<VerificationSessionResource> =
        noStore(
            HttpStatus.OK,
            service.revoke(
                RevokeVerificationSessionCommand(
                    jwt.actorId(),
                    sessionId,
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

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support verification request is invalid")
}
