package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderOverviewSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Service
internal class SupportLinkedOrderOverviewService(
    private val authorization: SupportTimelineAuthorization,
    private val orders: OrderingSupportTimelineOperations,
) {
    fun get(
        actorId: UUID,
        caseId: UUID,
        orderId: UUID,
    ): SupportOrderOverviewSnapshot {
        val scope = authorization.authorizeOrder(actorId, caseId, orderId)
        val overview =
            orders.findOrderOverviews(scope.orderIds).singleOrNull()
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Linked order overview is missing")
        if (overview.orderId != orderId) throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Linked order overview binding is invalid")
        authorization.recheckOrder(actorId, scope)
        return overview
    }
}

@RestController
internal class SupportLinkedOrderOverviewController(
    private val service: SupportLinkedOrderOverviewService,
) {
    @GetMapping("/api/v1/support/orders/{orderId}/overview")
    @PreAuthorize("isAuthenticated()")
    fun get(
        actor: OperatorActor,
        @PathVariable orderId: UUID,
        @RequestParam caseId: UUID,
    ): ResponseEntity<SupportOrderOverviewSnapshot> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(actor.actorId, caseId, orderId))
}
