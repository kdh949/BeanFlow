package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.DataAccessReasonCode
import io.github.kdh949.beanflow.support.internal.domain.SupportPersonalDataField
import io.github.kdh949.beanflow.support.internal.domain.VerificationPurpose
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class RequestDataAccessGrantRequest(
    @field:NotNull
    val verificationSessionId: UUID?,
    @field:NotNull
    val purpose: VerificationPurpose?,
    @field:NotEmpty @field:Size(max = 4)
    val fields: Set<SupportPersonalDataField>,
    @field:NotNull
    val reasonCode: DataAccessReasonCode?,
) : StrictSupportRequest

internal data class DecideDataAccessGrantRequest(
    @field:NotNull
    val decision: GrantDecision?,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotNull
    val reasonCode: DataAccessReasonCode?,
) : StrictSupportRequest

internal data class RevealGrantedPersonalDataRequest(
    @field:NotEmpty @field:Size(max = 4)
    val fields: Set<SupportPersonalDataField>,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class DataAccessGrantController(
    private val service: DataAccessGrantApplicationService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping("/cases/{caseId}/data-access-grants")
    @PreAuthorize("isAuthenticated()")
    fun request(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: RequestDataAccessGrantRequest,
    ): ResponseEntity<DataAccessGrantResource> =
        noStore(
            HttpStatus.CREATED,
            service.request(
                RequestDataAccessGrantCommand(
                    actor.actorId(),
                    caseId,
                    request.verificationSessionId ?: invalid(),
                    request.purpose ?: invalid(),
                    request.fields,
                    request.reasonCode ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/data-access-grants/{grantId}/approvals")
    @PreAuthorize("isAuthenticated()")
    fun decide(
        actor: OperatorActor,
        @PathVariable grantId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: DecideDataAccessGrantRequest,
    ): ResponseEntity<DataAccessGrantResource> =
        noStore(
            HttpStatus.OK,
            service.decide(
                DecideDataAccessGrantCommand(
                    actor.actorId(),
                    grantId,
                    request.decision ?: invalid(),
                    request.expectedVersion,
                    request.reasonCode ?: invalid(),
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/data-access-grants/{grantId}/reveals")
    @PreAuthorize("isAuthenticated()")
    fun reveal(
        actor: OperatorActor,
        @PathVariable grantId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: RevealGrantedPersonalDataRequest,
    ): ResponseEntity<RevealedPersonalDataResource> =
        noStore(
            HttpStatus.OK,
            service.reveal(
                RevealGrantedPersonalDataCommand(
                    actor.actorId(),
                    grantId,
                    request.fields,
                    idempotencyKey,
                    correlationIds.currentOrCreate(),
                ),
            ),
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

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "DataAccessGrant request is invalid")
}
