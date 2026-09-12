package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
internal class SupportCaseQueueController(
    private val service: SupportCaseApplicationService,
) {
    @GetMapping("/api/v1/support/case-queue/summary")
    @PreAuthorize("isAuthenticated()")
    fun summary(actor: OperatorActor): ResponseEntity<SupportCaseQueueSummaryResource> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.queueSummary(actor.actorId))
}
