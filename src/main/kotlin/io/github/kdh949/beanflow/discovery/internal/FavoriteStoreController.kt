package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.FavoriteStoreOperations
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/** Customer-scoped favorite store endpoints; the actor identity never comes from request input. */
@RestController
@RequestMapping("/api/v1/me")
internal class FavoriteStoreController(
    private val favorites: FavoriteStoreOperations,
    private val clock: Clock,
) {
    @GetMapping("/favorite-stores")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(actor: CustomerActor): CustomerStoreListResponse =
        CustomerStoreListResponse(favorites.list(customerId(actor), clock.instant()).map(CustomerStoreView::toResponse))

    @PutMapping("/favorite-stores/{storeId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun add(
        actor: CustomerActor,
        @PathVariable storeId: UUID,
    ): ResponseEntity<Void> {
        favorites.add(customerId(actor), storeId, clock.instant())
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/favorite-stores/{storeId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun remove(
        actor: CustomerActor,
        @PathVariable storeId: UUID,
    ): ResponseEntity<Void> {
        favorites.remove(customerId(actor), storeId)
        return ResponseEntity.noContent().build()
    }

    private fun customerId(actor: CustomerActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}
