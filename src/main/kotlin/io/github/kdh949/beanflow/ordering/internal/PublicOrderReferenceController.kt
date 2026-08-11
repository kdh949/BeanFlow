package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me/orders")
internal class PublicCustomerOrderController(
    private val service: PublicOrderReferenceService,
) {
    @GetMapping("/{orderReference}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderReference: String,
    ): PublicCustomerOrderResponse = service.getCustomerOrder(authenticatedId(jwt, "customer"), orderReference)

    @PostMapping("/{orderReference}/cancellations")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CustomerCancellationRequest,
    ): ResponseEntity<String> {
        val result =
            service.cancelCustomerOrder(
                authenticatedId(jwt, "customer"),
                orderReference,
                idempotencyKey,
                request,
            )
        return ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
    }
}

@RestController
@RequestMapping("/api/v1/stores/{storeId}/orders")
internal class PublicStoreOrderController(
    private val service: PublicOrderReferenceService,
) {
    @GetMapping("/{orderReference}")
    @PreAuthorize("hasAnyRole('STORE_OWNER', 'STORE_STAFF')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
    ): PublicStoreOrderResult = service.getStoreOrder(storeActor(jwt), storeId, orderReference)

    @PostMapping("/{orderReference}/transitions")
    @PreAuthorize("hasAnyRole('STORE_OWNER', 'STORE_STAFF')")
    fun transition(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: StoreOrderTransitionRequest,
    ): ResponseEntity<String> {
        val result = service.transitionStoreOrder(storeActor(jwt), storeId, orderReference, idempotencyKey, request)
        return ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
    }
}

private fun authenticatedId(
    jwt: Jwt,
    actorName: String,
): UUID =
    try {
        UUID.fromString(jwt.subject)
    } catch (_: IllegalArgumentException) {
        throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid $actorName ID")
    }

private fun storeActor(jwt: Jwt): StoreTransitionActor {
    val roles =
        buildSet {
            val claims = jwt.getClaimAsStringList("roles").orEmpty()
            if ("STORE_OWNER" in claims) add(StoreActorRole.OWNER)
            if ("STORE_STAFF" in claims) add(StoreActorRole.STAFF)
        }
    if (roles.isEmpty()) throw DomainFailure(FailureCode.ACCESS_DENIED, "Store role is required")
    return StoreTransitionActor(authenticatedId(jwt, "actor"), roles)
}
