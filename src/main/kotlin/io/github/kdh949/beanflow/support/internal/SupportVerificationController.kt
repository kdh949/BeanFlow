package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.VerificationActionScope
import io.github.kdh949.beanflow.support.internal.domain.VerificationChannel
import io.github.kdh949.beanflow.support.internal.domain.VerificationLevel
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CreateVerificationSessionRequest(
    @field:NotNull
    val subjectLinkId: UUID?,
    @field:NotNull
    val requestedLevel: VerificationLevel?,
    @field:NotNull
    val purpose: VerificationPurpose?,
    val actionScope: VerificationActionScope? = null,
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
        actor: OperatorActor,
        @PathVariable caseId: UUID,
    ): ResponseEntity<VerificationSessionResource> = retired()

    @GetMapping("/verification-sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: OperatorActor,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<VerificationSessionResource> = noStore(HttpStatus.OK, service.get(actor.actorId(), sessionId))

    @PostMapping("/verification-sessions/{sessionId}/challenges")
    @PreAuthorize("isAuthenticated()")
    fun issue(
        actor: OperatorActor,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<VerificationChallengeResource> = retired()

    @PostMapping("/verification-challenges/{challengeId}/verifications")
    @PreAuthorize("isAuthenticated()")
    fun verify(
        actor: OperatorActor,
        @PathVariable challengeId: UUID,
    ): ResponseEntity<VerificationResultResource> = retired()

    @PostMapping("/verification-sessions/{sessionId}/revocations")
    @PreAuthorize("isAuthenticated()")
    fun revoke(
        actor: OperatorActor,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<VerificationSessionResource> =
        noStore(
            HttpStatus.OK,
            service.revoke(
                RevokeVerificationSessionCommand(
                    actor.actorId(),
                    sessionId,
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    private fun retired(): Nothing =
        throw DomainFailure(
            FailureCode.SUPPORT_VERIFICATION_RETIRED,
            "고객센터 본인확인 절차가 종료되었습니다. 활성 상담의 대상 연결로 처리하세요.",
        )

    private fun OperatorActor.actorId(): UUID =
        try {
            actorId
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun <T : Any> noStore(
        status: HttpStatus,
        body: T,
    ): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support verification request is invalid")
}
