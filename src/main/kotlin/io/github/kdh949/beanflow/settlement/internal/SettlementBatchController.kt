package io.github.kdh949.beanflow.settlement.internal

import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
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
@RequestMapping("/api/v1/stores/{storeId}/settlements")
internal class SettlementBatchController(
    private val service: SettlementBatchQueryService,
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): SettlementBatchPageResponse =
        service.list(ListSettlementBatchesQuery(actor.actorId, setOf(StoreActorRole.OWNER), storeId, cursor, limit))
}
