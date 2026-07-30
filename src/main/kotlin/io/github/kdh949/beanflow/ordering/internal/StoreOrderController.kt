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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/store-orders")
internal class StoreOrderController(
    private val service: StoreOrderTransitionService,
) {
    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('STORE_OWNER', 'STORE_STAFF')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: UUID,
    ): StoreOrderResult = service.get(actor(jwt), orderId)

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasAnyRole('STORE_OWNER', 'STORE_STAFF')")
    fun transition(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: StoreOrderTransitionRequest,
    ): ResponseEntity<String> {
        val result = service.transition(actor(jwt), orderId, idempotencyKey, request)
        return ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body)
    }

    private fun actor(jwt: Jwt): StoreTransitionActor {
        val actorId =
            try {
                UUID.fromString(jwt.subject)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid actor ID")
            }
        val claims = jwt.getClaimAsStringList("roles").orEmpty()
        val roles =
            buildSet {
                if ("STORE_OWNER" in claims) add(StoreActorRole.OWNER)
                if ("STORE_STAFF" in claims) add(StoreActorRole.STAFF)
            }
        if (roles.isEmpty()) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Store role is required")
        }
        return StoreTransitionActor(actorId, roles)
    }
}
