package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.RecentStoreOperations
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/** Customer-owned BR-40 recent-store list. The customer id is never a query parameter. */
@RestController
@RequestMapping("/api/v1/me")
internal class RecentStoreController(
    private val recentStores: RecentStoreOperations,
    private val clock: Clock,
) {
    @GetMapping("/recent-stores")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) limit: String?,
    ): CustomerStoreListResponse =
        CustomerStoreListResponse(recentStores.list(customerId(actor), limit, clock.instant()).map { it.toResponse() })

    private fun customerId(actor: CustomerActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}
