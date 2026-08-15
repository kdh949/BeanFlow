package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.discovery.api.RecommendationReason
import io.github.kdh949.beanflow.discovery.api.StoreRecommendation
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationCommand
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationList
import io.github.kdh949.beanflow.discovery.api.StoreRecommendationOperations
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

internal data class StoreRecommendationResponse(
    val store: CustomerStoreResponse,
    val reason: RecommendationReason,
)

internal data class StoreRecommendationListResponse(
    val items: List<StoreRecommendationResponse>,
)

/** Customer home recommendation endpoint with optional request-scoped nearby coordinates. */
@RestController
@RequestMapping("/api/v1/me")
internal class StoreRecommendationController(
    private val recommendations: StoreRecommendationOperations,
    private val clock: Clock,
) {
    @GetMapping("/store-recommendations")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) latitude: String?,
        @RequestParam(required = false) longitude: String?,
        @RequestParam(required = false) radiusMeters: String?,
        @RequestParam(required = false) limit: String?,
    ): StoreRecommendationListResponse {
        val result =
            recommendations.list(
                customerId(actor),
                StoreRecommendationCommand(latitude, longitude, radiusMeters, limit, clock.instant()),
            )
        return result.toResponse()
    }

    private fun customerId(actor: CustomerActor): UUID =
        try {
            actor.actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Authenticated subject is not a valid customer ID")
        }
}

private fun StoreRecommendationList.toResponse() = StoreRecommendationListResponse(items.map(StoreRecommendation::toResponse))

private fun StoreRecommendation.toResponse() = StoreRecommendationResponse(store = store.toResponse(), reason = reason)
