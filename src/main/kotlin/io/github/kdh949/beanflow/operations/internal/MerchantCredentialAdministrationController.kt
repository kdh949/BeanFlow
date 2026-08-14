package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.MerchantCredentialMembershipRole
import io.github.kdh949.beanflow.operations.api.ProvisionedMerchantCredential
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
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

internal data class CreateMerchantAccountRequest(
    @field:Size(min = 5, max = 32)
    val loginId: String,
    @field:Size(min = 1, max = 100)
    val displayName: String,
    val storeId: UUID,
    val membershipRole: MerchantCredentialMembershipRole,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

internal data class MerchantCredentialReasonRequest(
    @field:Size(min = 1, max = 200)
    val reason: String,
)

internal data class MerchantMembershipResponse(
    val storeId: UUID,
    val role: MerchantCredentialMembershipRole,
)

internal data class MerchantAccountResponse(
    val merchantAccountId: UUID,
    val loginId: String,
    val displayName: String,
    val accountState: String,
    val lockedUntil: Instant?,
    val temporaryPasswordExpiresAt: Instant?,
    val memberships: List<MerchantMembershipResponse>,
)

internal data class MerchantTemporaryPasswordResponse(
    val merchantAccountId: UUID,
    val loginId: String? = null,
    val accountState: String,
    val membership: MerchantMembershipResponse? = null,
    val temporaryPassword: String,
    val temporaryPasswordExpiresAt: Instant,
)

@Validated
@RestController
@RequestMapping("/api/v1/operations/merchant-accounts")
internal class MerchantCredentialAdministrationController(
    private val service: MerchantCredentialAdministrationApplicationService,
) {
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun findExact(
        actor: OperatorActor,
        @RequestParam loginId: String,
        @RequestHeader("X-Access-Reason") reason: String,
    ): ResponseEntity<MerchantAccountResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.findExact(actorId(actor), loginId, reason).toResponse())

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun create(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateMerchantAccountRequest,
    ): ResponseEntity<MerchantTemporaryPasswordResponse> {
        val result =
            service.create(
                CreateMerchantAccountCommand(
                    actorId(actor),
                    idempotencyKey,
                    request.loginId,
                    request.displayName,
                    request.storeId,
                    request.membershipRole,
                    request.reason,
                ),
            )
        val account = result.account
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(
                MerchantTemporaryPasswordResponse(
                    merchantAccountId = account.accountId,
                    loginId = account.loginId,
                    accountState = account.accountState.name,
                    membership = account.memberships.single().let { MerchantMembershipResponse(it.storeId, it.role) },
                    temporaryPassword = result.temporaryPassword,
                    temporaryPasswordExpiresAt = checkNotNull(account.temporaryPasswordExpiresAt),
                ),
            )
    }

    @PostMapping("/{merchantAccountId}/temporary-password-resets")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun resetTemporaryPassword(
        actor: OperatorActor,
        @PathVariable merchantAccountId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: MerchantCredentialReasonRequest,
    ): ResponseEntity<MerchantTemporaryPasswordResponse> {
        val result = service.resetTemporaryPassword(command(actor, idempotencyKey, merchantAccountId, request.reason))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                MerchantTemporaryPasswordResponse(
                    merchantAccountId = result.account.accountId,
                    accountState = result.account.accountState.name,
                    temporaryPassword = result.temporaryPassword,
                    temporaryPasswordExpiresAt = checkNotNull(result.account.temporaryPasswordExpiresAt),
                ),
            )
    }

    @PostMapping("/{merchantAccountId}/lock-releases")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun releaseLock(
        actor: OperatorActor,
        @PathVariable merchantAccountId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: MerchantCredentialReasonRequest,
    ): ResponseEntity<Void> {
        service.releaseLock(command(actor, idempotencyKey, merchantAccountId, request.reason))
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    private fun command(
        actor: OperatorActor,
        key: String,
        accountId: UUID,
        reason: String,
    ) = MerchantCredentialMutationCommand(actorId(actor), key, accountId, reason)

    private fun ProvisionedMerchantCredential.toResponse() =
        MerchantAccountResponse(
            merchantAccountId = accountId,
            loginId = loginId,
            displayName = displayName,
            accountState = accountState.name,
            lockedUntil = lockedUntil,
            temporaryPasswordExpiresAt = temporaryPasswordExpiresAt,
            memberships = memberships.map { MerchantMembershipResponse(it.storeId, it.role) },
        )

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
