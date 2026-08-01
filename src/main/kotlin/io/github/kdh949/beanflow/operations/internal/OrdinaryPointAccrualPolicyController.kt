package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.ListOrdinaryPointAccrualPolicyVersionsCommand
import io.github.kdh949.beanflow.operations.api.ListStorePointAccrualPolicyHeadsCommand
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyPage
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyQueryOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySnapshot
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyState
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.operations.api.ReadOrdinaryPointAccrualPolicyCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class ChangeOrdinaryPointAccrualPolicyRequest(
    @field:Positive
    val expectedPolicyVersionId: Long?,
    val state: OrdinaryPointAccrualPolicyState,
    @field:Min(0)
    @field:Max(10_000)
    val accrualRateBps: Int?,
    val roundingMode: PointAccrualRoundingMode?,
    val issuerType: PointAccrualIssuerType?,
    @field:Size(min = 1, max = 240)
    val issuerReference: String?,
    val expiryRule: OrdinaryPointAccrualExpiryRule?,
    @field:Min(1)
    @field:Max(3650)
    val validityDays: Int?,
    @field:Size(min = 1, max = 500)
    val reason: String,
)

data class OrdinaryPointAccrualPolicyPageResponse<T>(
    val items: List<T>,
    val page: OrdinaryPointAccrualPolicyPageInfo,
)

data class OrdinaryPointAccrualPolicyPageInfo(
    val nextCursor: String?,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/policies/ordinary-point-accrual")
internal class OrdinaryPointAccrualPolicyController(
    private val queryOperations: OrdinaryPointAccrualPolicyQueryOperations,
    private val writeService: OrdinaryPointAccrualPolicyService,
    private val clock: Clock,
) {
    @GetMapping("/global")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun currentGlobal(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
    ) = queryOperations.currentGlobal(readCommand(jwt, accessReason))

    @GetMapping("/global/versions")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun globalHistory(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = queryOperations.globalHistory(historyCommand(jwt, accessReason, cursor, limit)).toResponse()

    @GetMapping("/stores")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun storeHeads(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
        @RequestParam(required = false) state: OrdinaryPointAccrualPolicyState?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = queryOperations
        .storeHeads(
            ListStorePointAccrualPolicyHeadsCommand(
                actorId(jwt),
                accessReason,
                state,
                cursor,
                limit,
                clock.instant(),
            ),
        ).toResponse()

    @GetMapping("/stores/{storeId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun currentStore(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
        @PathVariable storeId: UUID,
    ) = queryOperations.currentStore(storeId, readCommand(jwt, accessReason))

    @GetMapping("/stores/{storeId}/versions")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun storeHistory(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("X-Access-Reason") accessReason: String,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = queryOperations.storeHistory(storeId, historyCommand(jwt, accessReason, cursor, limit)).toResponse()

    @PatchMapping("/global")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun changeGlobal(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ChangeOrdinaryPointAccrualPolicyRequest,
    ) = writeService.change(
        request.toCommand(
            OrdinaryPointAccrualPolicyScopeType.GLOBAL,
            OrdinaryPointAccrualPolicySnapshot.GLOBAL_SCOPE_REFERENCE,
            actorId(jwt),
            idempotencyKey,
        ),
    )

    @PatchMapping("/stores/{storeId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun changeStore(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: ChangeOrdinaryPointAccrualPolicyRequest,
    ) = writeService.change(request.toCommand(OrdinaryPointAccrualPolicyScopeType.STORE, storeId, actorId(jwt), idempotencyKey))

    private fun readCommand(
        jwt: Jwt,
        accessReason: String,
    ) = ReadOrdinaryPointAccrualPolicyCommand(actorId(jwt), accessReason, clock.instant())

    private fun historyCommand(
        jwt: Jwt,
        accessReason: String,
        cursor: String?,
        limit: Int?,
    ) = ListOrdinaryPointAccrualPolicyVersionsCommand(actorId(jwt), accessReason, cursor, limit, clock.instant())

    private fun ChangeOrdinaryPointAccrualPolicyRequest.toCommand(
        scopeType: OrdinaryPointAccrualPolicyScopeType,
        scopeReference: UUID,
        actorId: UUID,
        idempotencyKey: String,
    ) = ChangeOrdinaryPointAccrualPolicyCommand(
        scopeType,
        scopeReference,
        state,
        accrualRateBps,
        roundingMode,
        issuerType,
        issuerReference,
        expiryRule,
        validityDays,
        expectedPolicyVersionId,
        actorId,
        idempotencyKey,
        reason,
        clock.instant(),
    )

    private fun <T> OrdinaryPointAccrualPolicyPage<T>.toResponse() =
        OrdinaryPointAccrualPolicyPageResponse(items, OrdinaryPointAccrualPolicyPageInfo(nextCursor))

    private fun actorId(jwt: Jwt): UUID =
        try {
            UUID.fromString(jwt.subject)
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
