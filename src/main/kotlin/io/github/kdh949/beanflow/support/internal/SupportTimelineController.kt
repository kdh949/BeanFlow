package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SupportTimelineSource
import io.github.kdh949.beanflow.shared.api.SupportTimelineType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/support")
internal class SupportTimelineController(
    private val service: SupportTimelineApplicationService,
) {
    @GetMapping("/cases/{caseId}/timeline")
    @PreAuthorize("isAuthenticated()")
    fun caseTimeline(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @RequestParam(required = false) sources: Set<SupportTimelineSource>?,
        @RequestParam(required = false) types: Set<SupportTimelineType>?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): ResponseEntity<SupportTimelinePageResource> =
        noStore(service.listCase(actor.actorId(), caseId, sources.orEmpty(), types.orEmpty(), cursor, limit))

    @GetMapping("/orders/{orderId}/timeline")
    @PreAuthorize("isAuthenticated()")
    fun orderTimeline(
        actor: OperatorActor,
        @PathVariable orderId: UUID,
        @RequestParam caseId: UUID,
        @RequestParam(required = false) sources: Set<SupportTimelineSource>?,
        @RequestParam(required = false) types: Set<SupportTimelineType>?,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): ResponseEntity<SupportTimelinePageResource> =
        noStore(service.listOrder(actor.actorId(), caseId, orderId, sources.orEmpty(), types.orEmpty(), cursor, limit))

    private fun OperatorActor.actorId(): UUID =
        try {
            actorId
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated actor is invalid")
        }

    private fun noStore(body: SupportTimelinePageResource): ResponseEntity<SupportTimelinePageResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
