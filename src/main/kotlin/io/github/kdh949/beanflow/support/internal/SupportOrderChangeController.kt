package io.github.kdh949.beanflow.support.internal

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeAuthorizationType
import io.github.kdh949.beanflow.support.internal.domain.SupportOrderChangeCostResponsibility
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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

internal data class CreateSupportOrderChangeAuthorizationRequest(
    @field:NotNull
    val authorizationType: SupportOrderChangeAuthorizationType?,
    @field:NotNull
    val action: SupportActionType?,
    @field:NotBlank @field:Size(max = 160)
    val policyVersion: String?,
    val requestId: UUID?,
    @field:Positive
    val revisionNumber: Int?,
    @field:PositiveOrZero
    val expectedRequestVersion: Long?,
    @field:NotNull
    val costResponsibility: SupportOrderChangeCostResponsibility?,
) : StrictSupportRequest

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "action")
@JsonSubTypes(
    JsonSubTypes.Type(value = ExecuteSupportOrderCancellationRequest::class, name = "ORDER_CANCELLATION"),
    JsonSubTypes.Type(value = ExecuteSupportPickupRescheduleRequest::class, name = "PICKUP_RESCHEDULE"),
)
internal sealed interface ExecuteSupportOrderChangeRequest : StrictSupportRequest {
    val revisionNumber: Int
    val expectedRequestVersion: Long
    val expectedTargetVersion: Long
    val authorizationId: UUID?
}

internal data class ExecuteSupportOrderCancellationRequest(
    @field:Positive
    override val revisionNumber: Int,
    @field:PositiveOrZero
    override val expectedRequestVersion: Long,
    @field:PositiveOrZero
    override val expectedTargetVersion: Long,
    @field:NotNull
    val reasonCode: CustomerCancellationReasonCode?,
    override val authorizationId: UUID?,
) : ExecuteSupportOrderChangeRequest

internal data class ExecuteSupportPickupRescheduleRequest(
    @field:Positive
    override val revisionNumber: Int,
    @field:PositiveOrZero
    override val expectedRequestVersion: Long,
    @field:PositiveOrZero
    override val expectedTargetVersion: Long,
    @field:NotNull
    val newPickupSlotId: UUID?,
    override val authorizationId: UUID?,
) : ExecuteSupportOrderChangeRequest

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/support-order-change-authorizations")
internal class SupportOrderChangeAuthorizationController(
    private val service: SupportOrderChangeAuthorizationApplicationService,
) {
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun create(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateSupportOrderChangeAuthorizationRequest,
    ): ResponseEntity<SupportOrderChangeAuthorizationResource> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(
                service.create(
                    CreateSupportOrderChangeAuthorizationCommand(
                        actor.actorId,
                        setOf(StoreActorRole.OWNER, StoreActorRole.STAFF),
                        storeId,
                        request.authorizationType ?: invalid(),
                        request.action ?: invalid(),
                        request.policyVersion ?: invalid(),
                        request.requestId,
                        request.revisionNumber,
                        request.expectedRequestVersion,
                        request.costResponsibility ?: invalid(),
                        idempotencyKey,
                    ),
                ),
            )
}

@Validated
@RestController
@RequestMapping("/api/v1/support/action-requests/{requestId}/executions")
internal class SupportOrderChangeExecutionController(
    private val service: SupportOrderChangeExecutionApplicationService,
) {
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun execute(
        actor: OperatorActor,
        @PathVariable requestId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ExecuteSupportOrderChangeRequest,
    ): ResponseEntity<SupportOrderChangeExecutionResource> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.execute(request.command(actor.actorId, requestId, idempotencyKey)))
}

private fun ExecuteSupportOrderChangeRequest.command(
    actorId: UUID,
    requestId: UUID,
    idempotencyKey: String,
): ExecuteSupportOrderChangeCommand =
    when (this) {
        is ExecuteSupportOrderCancellationRequest -> {
            ExecuteSupportOrderChangeCommand(
                actorId,
                requestId,
                SupportActionType.ORDER_CANCELLATION,
                revisionNumber,
                expectedRequestVersion,
                expectedTargetVersion,
                reasonCode ?: invalid(),
                null,
                authorizationId,
                idempotencyKey,
            )
        }

        is ExecuteSupportPickupRescheduleRequest -> {
            ExecuteSupportOrderChangeCommand(
                actorId,
                requestId,
                SupportActionType.PICKUP_RESCHEDULE,
                revisionNumber,
                expectedRequestVersion,
                expectedTargetVersion,
                null,
                newPickupSlotId ?: invalid(),
                authorizationId,
                idempotencyKey,
            )
        }
    }

private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support order change request is invalid")
