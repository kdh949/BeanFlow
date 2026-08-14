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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me/orders")
internal class PublicCustomerOrderController(
    private val service: PublicOrderReferenceService,
    private val queries: CustomerOrderQueryService,
) {
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) status: CustomerOrderStatusFilter?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): CustomerOrderPageResponse = queries.list(actor.actorId, status, from, to, cursor, limit)

    @GetMapping("/{orderReference}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun get(
        actor: CustomerActor,
        @PathVariable orderReference: String,
    ): CustomerOrderDetailResponse = queries.detail(actor.actorId, orderReference)

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
    private val board: StoreOrderBoardQueryService,
) {
    @GetMapping
    @PreAuthorize("hasRole('MERCHANT')")
    fun list(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) lane: StoreOrderBoardLane?,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<StoreOrderBoardResponse> = board.list(actor.actorId, storeId, lane, ifNoneMatch)

    @GetMapping("/overflow")
    @PreAuthorize("hasRole('MERCHANT')")
    fun overflow(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam lane: StoreOrderBoardLane,
        @RequestParam @Size(min = 1, max = 2048) cursor: String,
    ): StoreOrderBoardOverflowPageResponse = board.overflow(actor.actorId, storeId, lane, cursor)

    @GetMapping("/{orderReference}")
    @PreAuthorize("hasRole('MERCHANT')")
    fun get(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
    ): StoreOrderBoardItemResponse = board.detail(actor.actorId, storeId, orderReference)

    @PostMapping("/{orderReference}/transitions")
    @PreAuthorize("hasRole('MERCHANT')")
    fun transition(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable orderReference: String,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: StoreOrderActionRequest,
    ): ResponseEntity<String> {
        val result = service.transitionStoreOrderBoard(storeActor(actor), storeId, orderReference, idempotencyKey, request)
        return ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
    }
}

private fun storeActor(actor: MerchantActor): StoreTransitionActor =
    StoreTransitionActor(actor.actorId, setOf(StoreActorRole.OWNER, StoreActorRole.STAFF))
