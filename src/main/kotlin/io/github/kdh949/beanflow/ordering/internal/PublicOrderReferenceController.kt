package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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
        actor: CustomerActor,
        @PathVariable orderReference: String,
    ): PublicCustomerOrderResponse = service.getCustomerOrder(actor.actorId, orderReference)

    @PostMapping("/{orderReference}/cancellations")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun cancel(
        actor: CustomerActor,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CustomerCancellationRequest,
    ): ResponseEntity<String> {
        val result =
            service.cancelCustomerOrder(
                actor.actorId,
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
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
    ): PublicStoreOrderResult = service.getStoreOrder(storeActor(actor), storeId, orderReference)

    @PostMapping("/{orderReference}/transitions")
    @PreAuthorize("isAuthenticated()")
    fun transition(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: StoreOrderTransitionRequest,
    ): ResponseEntity<String> {
        val result = service.transitionStoreOrder(storeActor(actor), storeId, orderReference, idempotencyKey, request)
        return ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
    }
}

private fun storeActor(actor: MerchantActor): StoreTransitionActor =
    StoreTransitionActor(actor.actorId, setOf(StoreActorRole.OWNER, StoreActorRole.STAFF))
