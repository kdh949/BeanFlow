package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: MerchantActor,
        @PathVariable orderId: UUID,
    ): StoreOrderResult = service.get(actor.toStoreTransitionActor(), orderId)

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("isAuthenticated()")
    fun transition(
        actor: MerchantActor,
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: StoreOrderTransitionRequest,
    ): ResponseEntity<String> {
        val result = service.transition(actor.toStoreTransitionActor(), orderId, idempotencyKey, request)
        return ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body)
    }
}

private fun MerchantActor.toStoreTransitionActor(): StoreTransitionActor =
    StoreTransitionActor(actorId, setOf(StoreActorRole.OWNER, StoreActorRole.STAFF))
