package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.RegionSnapshot
import io.github.kdh949.beanflow.shared.api.MerchantActor
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RegionResponse(
    val code: String,
    val sido: String,
    val sigungu: String,
    val eupmyeondong: String,
    val ri: String,
    val fullName: String,
) {
    companion object {
        fun of(region: RegionSnapshot) =
            RegionResponse(region.code, region.sido, region.sigungu, region.eupmyeondong, region.ri, region.fullName)
    }
}

data class RegionPageResponse(
    val items: List<RegionResponse>,
    val page: RegionPageInfo,
)

data class RegionPageInfo(
    val nextCursor: String?,
)

/**
 * The 법정동 vocabulary a store owner picks from (ADR-112 2절).
 *
 * The store owner is the one who knows the store's address, so the picker is on the merchant chain
 * rather than the operator one. It exposes reference data only: no store, coordinate or search term
 * is reachable from here.
 */
@Validated
@RestController
@RequestMapping("/api/v1")
internal class RegionCatalogController(
    private val service: RegionCatalogService,
) {
    @GetMapping("/regions")
    fun list(
        actor: MerchantActor,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): RegionPageResponse {
        actor.actorId
        val page = service.list(query, cursor, limit)
        return RegionPageResponse(page.regions.map(RegionResponse::of), RegionPageInfo(page.nextCursor))
    }
}
