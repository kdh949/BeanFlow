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
@RequestMapping("/api/v1/stores/{storeId}/settlements/{settlementBatchId}/items")
internal class SettlementItemController(
    private val service: SettlementItemQueryService,
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable settlementBatchId: UUID,
        @RequestParam(required = false) @Size(min = 1, max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(100) limit: Int?,
    ): SettlementItemPageResponse =
        service.list(
            ListSettlementItemsQuery(
                actor.actorId,
                // 정산 명세는 수수료·혜택 원가·실지급액이라 batch 목록과 같은 ACTIVE OWNER 전용이다.
                setOf(StoreActorRole.OWNER),
                storeId,
                settlementBatchId,
                cursor,
                limit,
            ),
        )
}
