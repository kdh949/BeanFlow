package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
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

internal data class CreateRepairProposalRequest(
    @field:Size(min = 1, max = 500)
    val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown repair request field")
}

internal enum class RepairProposalDecision {
    APPROVE,
    REJECT,
}

internal data class RepairProposalDecisionRequest(
    val decision: RepairProposalDecision,
    @field:Size(min = 1, max = 500)
    val reason: String,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown repair decision field")
}

internal data class RepairProposal(
    val proposalId: UUID,
    val caseId: UUID,
    val action: PaymentSetupRepairAction,
    val state: PaymentSetupRepairProposalState,
    val proposedBy: UUID,
    val decidedBy: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val decidedAt: Instant?,
    val correlationId: String,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations")
internal class PaymentSetupRepairController(
    private val service: PaymentSetupRepairService,
    private val clock: Clock,
) {
    @PostMapping("/reprocessing-cases/{caseId}/repair-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun propose(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: CreateRepairProposalRequest,
    ): RepairProposal =
        service.propose(
            ProposePaymentSetupRepairCommand(
                actorId = actorId(jwt),
                caseId = caseId,
                idempotencyKey = idempotencyKey,
                reason = request.reason,
                now = clock.instant(),
            ),
        )

    @PostMapping("/reprocessing-repair-proposals/{proposalId}/decisions")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun decide(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable proposalId: UUID,
        @Valid @RequestBody request: RepairProposalDecisionRequest,
    ): RepairProposal =
        when (
            val outcome =
                service.decide(
                    DecidePaymentSetupRepairCommand(
                        actorId = actorId(jwt),
                        proposalId = proposalId,
                        decision = request.decision,
                        idempotencyKey = idempotencyKey,
                        reason = request.reason,
                        now = clock.instant(),
                    ),
                )
        ) {
            is PaymentSetupRepairDecisionOutcome.Succeeded -> outcome.proposal
            is PaymentSetupRepairDecisionOutcome.Failed -> throw DomainFailure(outcome.code, outcome.message)
        }

    private fun actorId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
