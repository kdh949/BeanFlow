package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationMode
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicySnapshot
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class UpdateExpiredBenefitRestorationPolicyRequest(
    @field:Min(1)
    val expectedPolicyVersion: Long,
    val mode: ExpiredBenefitRestorationMode,
    @field:Min(1)
    @field:Max(365)
    val compensationValidityDays: Int,
    @field:Size(min = 1, max = 500)
    val reason: String,
)

@RestController
@RequestMapping("/api/v1/operations/policies/expired-benefit-restoration")
internal class OperationsPolicyController(
    private val operations: ExpiredBenefitRestorationPolicyOperations,
    private val clock: Clock,
) {
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun get(): ExpiredBenefitRestorationPolicySnapshot = operations.current()

    @PatchMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: UpdateExpiredBenefitRestorationPolicyRequest,
    ): ExpiredBenefitRestorationPolicySnapshot =
        operations.update(
            UpdateExpiredBenefitRestorationPolicyCommand(
                actorId = actorId(jwt),
                idempotencyKey = idempotencyKey,
                expectedPolicyVersion = request.expectedPolicyVersion,
                mode = request.mode,
                compensationValidityDays = request.compensationValidityDays,
                reason = request.reason,
                now = clock.instant(),
            ),
        )

    private fun actorId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid actor ID")
        }
}
