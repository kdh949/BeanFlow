package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class EvaluateSupportActionRequest(
    @field:NotNull
    val action: SupportActionType?,
    @field:NotNull
    val orderId: UUID?,
    @field:NotNull @field:PositiveOrZero
    val expectedTargetVersion: Long?,
    @field:NotNull
    val verificationSessionId: UUID?,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportActionEvaluationController(
    private val service: SupportActionEvaluationApplicationService,
) {
    @PostMapping("/cases/{caseId}/action-evaluations")
    @PreAuthorize("isAuthenticated()")
    fun evaluate(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: EvaluateSupportActionRequest,
    ): ResponseEntity<SupportActionEvaluationResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                service.evaluate(
                    EvaluateSupportActionCommand(
                        actorId = actor.actorId(),
                        caseId = caseId,
                        action = request.action ?: invalid(),
                        orderId = request.orderId ?: invalid(),
                        expectedTargetVersion = request.expectedTargetVersion ?: invalid(),
                        verificationSessionId = request.verificationSessionId ?: invalid(),
                    ),
                ),
            )

    private fun OperatorActor.actorId(): UUID =
        try {
            actorId
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support action evaluation is invalid")
}
