package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyHead
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.operations.api.ListExpiredBenefitRestorationPoliciesCommand
import io.github.kdh949.beanflow.operations.api.UpdateExpiredBenefitRestorationPolicyCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class UpdateExpiredBenefitRestorationPolicyRequest(
    @field:Min(1)
    val expectedPolicyVersionId: Long,
    val mode: ExpiredBenefitRestorationMode,
    @field:Min(1)
    @field:Max(365)
    val compensationValidityDays: Int,
    @field:Size(min = 1, max = 500)
    val reason: String,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/policies/expired-benefit-restoration")
internal class OperationsPolicyController(
    private val operations: ExpiredBenefitRestorationPolicyOperations,
    private val clock: Clock,
) {
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
    ): List<ExpiredBenefitRestorationPolicyHead> =
        operations.listCurrent(
            ListExpiredBenefitRestorationPoliciesCommand(
                actorId = actorId(jwt),
                accessReason = accessReason,
                now = clock.instant(),
            ),
        )

    @PatchMapping("/{trigger}/{benefitType}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable trigger: ExpiredBenefitRestorationTrigger,
        @PathVariable benefitType: ExpiredBenefitType,
        @Valid @RequestBody request: UpdateExpiredBenefitRestorationPolicyRequest,
    ): ExpiredBenefitRestorationPolicyHead =
        operations.update(
            UpdateExpiredBenefitRestorationPolicyCommand(
                actorId = actorId(jwt),
                idempotencyKey = idempotencyKey,
                trigger = trigger,
                benefitType = benefitType,
                expectedPolicyVersionId = request.expectedPolicyVersionId,
                mode = request.mode,
                compensationValidityDays = request.compensationValidityDays,
                reason = request.reason,
                now = clock.instant(),
            ),
        )

    private fun actorId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
