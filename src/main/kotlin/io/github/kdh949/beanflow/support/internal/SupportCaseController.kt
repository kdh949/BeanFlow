package io.github.kdh949.beanflow.support.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.SupportRequesterType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

internal data class CreateSupportCaseRequest(
    @field:NotNull
    val requesterType: SupportRequesterType?,
    @field:NotBlank @field:Size(max = 200)
    val requesterReference: String,
    @field:NotNull
    val category: SupportInquiryCategory?,
    @field:NotNull
    val priority: SupportCasePriority?,
    @field:Size(max = 200)
    val externalReference: String? = null,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal data class AssignSupportCaseRequest(
    @field:NotNull
    val assigneeId: UUID?,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal data class TransitionSupportCaseRequest(
    @field:NotNull
    val targetState: SupportCaseState?,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal data class AppendSupportInteractionRequest(
    @field:NotNull
    val channel: SupportInteractionChannel?,
    @field:NotNull
    val direction: SupportInteractionDirection?,
    @field:NotNull
    val occurredAt: Instant?,
    @field:NotBlank @field:Size(max = 1000)
    val redactedSummary: String,
) : StrictSupportRequest

internal data class AppendSupportNoteRequest(
    @field:NotBlank @field:Size(max = 2000)
    val content: String,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal data class LinkSupportSubjectRequest(
    @field:NotNull
    val subjectType: SupportSubjectType?,
    @field:NotNull
    val subjectId: UUID?,
    @field:NotNull
    val relationship: SupportSubjectRelationship?,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal data class UnlinkSupportSubjectRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
) : StrictSupportRequest

internal interface StrictSupportRequest {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") field: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Support request contains an unexpected field")
}

@Validated
@RestController
@RequestMapping("/api/v1/support/cases")
internal class SupportCaseController(
    private val service: SupportCaseApplicationService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun create(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateSupportCaseRequest,
    ): ResponseEntity<SupportCaseResource> =
        noStore(
            HttpStatus.CREATED,
            service.create(
                CreateSupportCaseCommand(
                    actorId = actor.actorId(),
                    idempotencyKey = idempotencyKey,
                    requesterType = request.requesterType ?: invalidEnum(),
                    requesterReference = request.requesterReference,
                    category = request.category ?: invalidEnum(),
                    priority = request.priority ?: invalidEnum(),
                    externalReference = request.externalReference,
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) state: SupportCaseState?,
        @RequestParam(required = false) assigneeId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) @PositiveOrZero limit: Int?,
    ): ResponseEntity<SupportCasePageResource> = noStore(HttpStatus.OK, service.list(actor.actorId(), state, assigneeId, cursor, limit))

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
    ): ResponseEntity<SupportCaseResource> = noStore(HttpStatus.OK, service.get(actor.actorId(), caseId))

    @PostMapping("/{caseId}/assignments")
    @PreAuthorize("isAuthenticated()")
    fun assign(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: AssignSupportCaseRequest,
    ): ResponseEntity<SupportCaseAssignmentResource> =
        noStore(
            HttpStatus.OK,
            service.assign(
                AssignSupportCaseCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    idempotencyKey = idempotencyKey,
                    assigneeId = request.assigneeId ?: invalidEnum(),
                    expectedVersion = request.expectedVersion,
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/{caseId}/status-transitions")
    @PreAuthorize("isAuthenticated()")
    fun transition(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: TransitionSupportCaseRequest,
    ): ResponseEntity<SupportCaseTransitionResource> =
        noStore(
            HttpStatus.OK,
            service.transition(
                TransitionSupportCaseCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    idempotencyKey = idempotencyKey,
                    targetState = request.targetState ?: invalidEnum(),
                    expectedVersion = request.expectedVersion,
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/{caseId}/interactions")
    @PreAuthorize("isAuthenticated()")
    fun appendInteraction(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: AppendSupportInteractionRequest,
    ): ResponseEntity<SupportInteractionResource> =
        noStore(
            HttpStatus.OK,
            service.appendInteraction(
                AppendSupportInteractionCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    idempotencyKey = idempotencyKey,
                    channel = request.channel ?: invalidEnum(),
                    direction = request.direction ?: invalidEnum(),
                    occurredAt = request.occurredAt ?: invalidEnum(),
                    redactedSummary = request.redactedSummary,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/{caseId}/notes")
    @PreAuthorize("isAuthenticated()")
    fun appendNote(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: AppendSupportNoteRequest,
    ): ResponseEntity<SupportNoteResource> =
        noStore(
            HttpStatus.OK,
            service.appendNote(
                AppendSupportNoteCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    idempotencyKey = idempotencyKey,
                    content = request.content,
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @PostMapping("/{caseId}/subject-links")
    @PreAuthorize("isAuthenticated()")
    fun linkSubject(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: LinkSupportSubjectRequest,
    ): ResponseEntity<SupportSubjectLinkResource> =
        noStore(
            HttpStatus.OK,
            service.linkSubject(
                LinkSupportSubjectCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    idempotencyKey = idempotencyKey,
                    subjectType = request.subjectType ?: invalidEnum(),
                    subjectId = request.subjectId ?: invalidEnum(),
                    relationship = request.relationship ?: invalidEnum(),
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    @DeleteMapping("/{caseId}/subject-links/{linkId}")
    @PreAuthorize("isAuthenticated()")
    fun unlinkSubject(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @PathVariable linkId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: UnlinkSupportSubjectRequest,
    ): ResponseEntity<SupportSubjectUnlinkResource> =
        noStore(
            HttpStatus.OK,
            service.unlinkSubject(
                UnlinkSupportSubjectCommand(
                    actorId = actor.actorId(),
                    caseId = caseId,
                    linkId = linkId,
                    idempotencyKey = idempotencyKey,
                    expectedVersion = request.expectedVersion,
                    reason = request.reason,
                    correlationId = correlationIds.currentOrCreate(),
                ),
            ),
        )

    private fun OperatorActor.actorId(): UUID =
        try {
            actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid actor ID")
        }

    private fun <T : Any> noStore(
        status: HttpStatus,
        body: T,
    ): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private fun invalidEnum(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "SupportCase request is invalid")
}
