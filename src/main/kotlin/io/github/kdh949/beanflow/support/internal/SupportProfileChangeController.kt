package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
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
import java.util.UUID

internal abstract class SensitiveProfileChangeRequest : StrictSupportRequest {
    final override fun toString(): String = "SensitiveProfileChangeRequest(values=<redacted>)"
}

internal data class ProfileChangeBindingRequest(
    @field:NotNull val subjectId: UUID?,
    @field:PositiveOrZero val expectedProfileVersion: Long,
    @field:NotNull val verificationSessionId: UUID?,
    @field:NotBlank @field:Size(max = 500) val reason: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$") val evidenceDigest: String?,
) : SensitiveProfileChangeRequest()

internal data class ProfileChangeRevisionBindingRequest(
    @field:PositiveOrZero val expectedProfileChangeVersion: Long,
    @field:PositiveOrZero val expectedActionRequestVersion: Long,
    @field:PositiveOrZero val expectedProfileVersion: Long,
    @field:NotNull val verificationSessionId: UUID?,
    @field:NotBlank @field:Size(max = 500) val reason: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9a-f]{64}$") val evidenceDigest: String?,
) : SensitiveProfileChangeRequest()

internal data class ProfileChangeExecutionBindingRequest(
    @field:Positive val revisionNumber: Int,
    @field:PositiveOrZero val expectedActionRequestVersion: Long,
    @field:PositiveOrZero val expectedProfileChangeVersion: Long,
    @field:PositiveOrZero val expectedProfileVersion: Long,
) : SensitiveProfileChangeRequest()

internal data class CustomerDisplayNameRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Size(max = 200) val displayName: String?,
) : SensitiveProfileChangeRequest()

internal data class CustomerLegalNameRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Size(max = 200) val legalName: String?,
) : SensitiveProfileChangeRequest()

internal data class CustomerPrimaryPhoneRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Size(max = 32) val primaryPhone: String?,
) : SensitiveProfileChangeRequest()

internal data class CustomerCredentialResetRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class StorePublicProfileRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:Size(max = 200) val displayName: String?,
    @field:Size(max = 32) val publicPhone: String?,
    @field:Size(max = 1000) val description: String?,
    @field:Size(max = 1000) val pickupInstructions: String?,
) : SensitiveProfileChangeRequest() {
    @get:AssertTrue(message = "At least one nonblank store public-profile field is required")
    val hasValidChange: Boolean
        get() = listOf(displayName, publicPhone, description, pickupInstructions).validOptionalChange()
}

internal data class StoreOperationsContactRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:Size(max = 32) val phone: String?,
    @field:Email @field:Size(max = 320) val email: String?,
) : SensitiveProfileChangeRequest() {
    @get:AssertTrue(message = "At least one nonblank store operations-contact field is required")
    val hasValidChange: Boolean
        get() = listOf(phone, email).validOptionalChange()
}

internal data class StoreRepresentativeRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Size(max = 200) val representativeName: String?,
) : SensitiveProfileChangeRequest()

internal data class StoreSettlementAccountRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val accountReference: String?,
) : SensitiveProfileChangeRequest()

internal data class StoreAccessReregistrationRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class CourierDisplayNameRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Size(max = 200) val displayName: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierRelayContactRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:Size(max = 32) val phone: String?,
    @field:Email @field:Size(max = 320) val email: String?,
) : SensitiveProfileChangeRequest() {
    @get:AssertTrue(message = "At least one nonblank courier relay-contact field is required")
    val hasValidChange: Boolean
        get() = listOf(phone, email).validOptionalChange()
}

private fun List<String?>.validOptionalChange(): Boolean = any { it != null } && filterNotNull().all { it.isNotBlank() }

internal data class CourierProviderIdentityRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val providerReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierPayoutReferenceRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val payoutReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierProviderReregistrationRequest(
    @field:Valid val binding: ProfileChangeBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class CustomerPrimaryPhoneRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
    @field:NotBlank @field:Size(max = 32) val primaryPhone: String?,
) : SensitiveProfileChangeRequest()

internal data class CustomerCredentialResetRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class StoreRepresentativeRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
    @field:NotBlank @field:Size(max = 200) val representativeName: String?,
) : SensitiveProfileChangeRequest()

internal data class StoreSettlementAccountRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val accountReference: String?,
) : SensitiveProfileChangeRequest()

internal data class StoreAccessReregistrationRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class CourierProviderIdentityRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val providerReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierPayoutReferenceRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val payoutReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierProviderReregistrationRevisionRequest(
    @field:Valid val binding: ProfileChangeRevisionBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class CustomerPrimaryPhoneExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
    @field:NotBlank @field:Size(max = 32) val primaryPhone: String?,
) : SensitiveProfileChangeRequest()

internal data class EmptyProfileExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
) : SensitiveProfileChangeRequest()

internal data class StoreRepresentativeExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
    @field:NotBlank @field:Size(max = 200) val representativeName: String?,
) : SensitiveProfileChangeRequest()

internal data class StoreSettlementAccountExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val accountReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierProviderIdentityExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val providerReference: String?,
) : SensitiveProfileChangeRequest()

internal data class CourierPayoutReferenceExecutionRequest(
    @field:Valid val binding: ProfileChangeExecutionBindingRequest,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9:_-]{4,200}$") val payoutReference: String?,
) : SensitiveProfileChangeRequest()

internal data class RetryProfileNotificationsRequest(
    @field:PositiveOrZero val expectedProfileChangeVersion: Long,
) : StrictSupportRequest

@Validated
@RestController
@RequestMapping("/api/v1/support/cases/{caseId}/profile-changes")
internal class SupportProfileChangeCreateController(
    private val service: SupportProfileChangeApplicationService,
) {
    @PostMapping("/customer-display-name-corrections")
    @PreAuthorize("isAuthenticated()")
    fun customerDisplayName(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerDisplayNameRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CustomerDisplayName(request.displayName!!),
            ),
        ),
    )

    @PostMapping("/customer-legal-name-corrections")
    @PreAuthorize("isAuthenticated()")
    fun customerLegalName(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerLegalNameRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CustomerLegalName(request.legalName!!),
            ),
        ),
    )

    @PostMapping("/customer-primary-phone-requests")
    @PreAuthorize("isAuthenticated()")
    fun customerPrimaryPhone(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerPrimaryPhoneRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CustomerPrimaryPhone(request.primaryPhone!!),
            ),
        ),
    )

    @PostMapping("/customer-credential-reset-requests")
    @PreAuthorize("isAuthenticated()")
    fun customerCredentialReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerCredentialResetRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CustomerCredentialReset,
            ),
        ),
    )

    @PostMapping("/store-public-profile-corrections")
    @PreAuthorize("isAuthenticated()")
    fun storePublicProfile(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StorePublicProfileRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.StorePublicProfile(
                    request.displayName,
                    request.publicPhone,
                    request.description,
                    request.pickupInstructions,
                ),
            ),
        ),
    )

    @PostMapping("/store-operations-contact-corrections")
    @PreAuthorize("isAuthenticated()")
    fun storeOperationsContact(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreOperationsContactRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.StoreOperationsContact(request.phone, request.email),
            ),
        ),
    )

    @PostMapping("/store-representative-requests")
    @PreAuthorize("isAuthenticated()")
    fun storeRepresentative(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreRepresentativeRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.StoreRepresentative(request.representativeName!!),
            ),
        ),
    )

    @PostMapping("/store-settlement-account-requests")
    @PreAuthorize("isAuthenticated()")
    fun storeSettlement(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreSettlementAccountRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.StoreSettlementAccount(request.accountReference!!),
            ),
        ),
    )

    @PostMapping("/store-access-reregistration-requests")
    @PreAuthorize("isAuthenticated()")
    fun storeAccess(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreAccessReregistrationRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.StoreAccessReregistration,
            ),
        ),
    )

    @PostMapping("/courier-display-name-corrections")
    @PreAuthorize("isAuthenticated()")
    fun courierDisplay(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierDisplayNameRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CourierDisplayName(request.displayName!!),
            ),
        ),
    )

    @PostMapping("/courier-relay-contact-corrections")
    @PreAuthorize("isAuthenticated()")
    fun courierRelay(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierRelayContactRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CourierRelayContact(request.phone, request.email),
            ),
        ),
    )

    @PostMapping("/courier-provider-identity-requests")
    @PreAuthorize("isAuthenticated()")
    fun courierIdentity(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierProviderIdentityRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CourierProviderIdentity(request.providerReference!!),
            ),
        ),
    )

    @PostMapping("/courier-payout-reference-requests")
    @PreAuthorize("isAuthenticated()")
    fun courierPayout(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierPayoutReferenceRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CourierPayoutReference(request.payoutReference!!),
            ),
        ),
    )

    @PostMapping("/courier-provider-reregistration-requests")
    @PreAuthorize("isAuthenticated()")
    fun courierReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierProviderReregistrationRequest,
    ) = created(
        service.submit(
            request.binding.submit(
                jwt.actorId(),
                caseId,
                key,
                SupportProfileChangePayload.CourierProviderReregistration,
            ),
        ),
    )

    private fun created(resource: SupportProfileChangeResource): ResponseEntity<SupportProfileChangeResource> =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(resource)
}

@Validated
@RestController
@RequestMapping("/api/v1/support/profile-changes")
internal class SupportProfileChangeWorkflowController(
    private val service: SupportProfileChangeApplicationService,
) {
    @GetMapping("/{profileChangeId}")
    @PreAuthorize("isAuthenticated()")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
    ) = ok(service.get(jwt.actorId(), profileChangeId))

    @PostMapping("/{profileChangeId}/customer-primary-phone-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseCustomerPhone(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerPrimaryPhoneRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CustomerPrimaryPhone(request.primaryPhone!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/customer-credential-reset-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseCustomerReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerCredentialResetRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CustomerCredentialReset,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-representative-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseStoreRepresentative(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreRepresentativeRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreRepresentative(request.representativeName!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-settlement-account-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseStoreSettlement(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreSettlementAccountRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreSettlementAccount(request.accountReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-access-reregistration-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseStoreAccess(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreAccessReregistrationRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreAccessReregistration,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-provider-identity-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseCourierIdentity(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierProviderIdentityRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierProviderIdentity(request.providerReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-payout-reference-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseCourierPayout(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierPayoutReferenceRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierPayoutReference(request.payoutReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-provider-reregistration-revisions")
    @PreAuthorize("isAuthenticated()")
    fun reviseCourierReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierProviderReregistrationRevisionRequest,
    ) = ok(
        service.revise(
            request.binding.revise(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierProviderReregistration,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/customer-primary-phone-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeCustomerPhone(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CustomerPrimaryPhoneExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CustomerPrimaryPhone(request.primaryPhone!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/customer-credential-reset-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeCustomerReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: EmptyProfileExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CustomerCredentialReset,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-representative-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeStoreRepresentative(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreRepresentativeExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreRepresentative(request.representativeName!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-settlement-account-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeStoreSettlement(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: StoreSettlementAccountExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreSettlementAccount(request.accountReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/store-access-reregistration-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeStoreAccess(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: EmptyProfileExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.StoreAccessReregistration,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-provider-identity-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeCourierIdentity(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierProviderIdentityExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierProviderIdentity(request.providerReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-payout-reference-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeCourierPayout(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: CourierPayoutReferenceExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierPayoutReference(request.payoutReference!!),
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/courier-provider-reregistration-executions")
    @PreAuthorize("isAuthenticated()")
    fun executeCourierReset(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: EmptyProfileExecutionRequest,
    ) = ok(
        service.execute(
            request.binding.execute(
                jwt.actorId(),
                profileChangeId,
                key,
                SupportProfileChangePayload.CourierProviderReregistration,
            ),
        ),
    )

    @PostMapping("/{profileChangeId}/notification-retries")
    @PreAuthorize("isAuthenticated()")
    fun retry(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable profileChangeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody request: RetryProfileNotificationsRequest,
    ) = ok(
        service.retryNotifications(
            RetrySupportProfileNotificationCommand(
                jwt.actorId(),
                profileChangeId,
                request.expectedProfileChangeVersion,
                key,
            ),
        ),
    )

    private fun ok(resource: SupportProfileChangeResource): ResponseEntity<SupportProfileChangeResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resource)
}

private fun ProfileChangeBindingRequest.submit(
    actorId: UUID,
    caseId: UUID,
    key: String,
    payload: SupportProfileChangePayload,
) = SubmitSupportProfileChangeCommand(
    actorId,
    caseId,
    subjectId!!,
    expectedProfileVersion,
    verificationSessionId!!,
    reason!!,
    evidenceDigest!!,
    key,
    payload,
)

private fun ProfileChangeRevisionBindingRequest.revise(
    actorId: UUID,
    id: UUID,
    key: String,
    payload: SupportProfileChangePayload,
) = ReviseSupportProfileChangeCommand(
    actorId,
    id,
    expectedProfileChangeVersion,
    expectedActionRequestVersion,
    expectedProfileVersion,
    verificationSessionId!!,
    reason!!,
    evidenceDigest!!,
    key,
    payload,
)

private fun ProfileChangeExecutionBindingRequest.execute(
    actorId: UUID,
    id: UUID,
    key: String,
    payload: SupportProfileChangePayload,
) = ExecuteSupportProfileChangeCommand(
    actorId,
    id,
    revisionNumber,
    expectedActionRequestVersion,
    expectedProfileChangeVersion,
    expectedProfileVersion,
    key,
    payload,
)

private fun Jwt.actorId(): UUID =
    try {
        UUID.fromString(subject)
    } catch (_: IllegalArgumentException) {
        throw DomainFailure(FailureCode.INVALID_REQUEST, "Support profile change actor is invalid")
    }
